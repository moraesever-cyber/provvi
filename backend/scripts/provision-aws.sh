#!/usr/bin/env bash
# provision-aws.sh — Provisionamento idempotente do backend Provvi na AWS
#
# Uso: ./provision-aws.sh dev
#      ./provision-aws.sh prod
#
# O script verifica a existência de cada recurso antes de criá-lo,
# tornando seguro executar múltiplas vezes sem efeitos colaterais.

set -euo pipefail

# ---------------------------------------------------------------------------
# Validação do parâmetro de ambiente
# ---------------------------------------------------------------------------

if [[ $# -lt 1 ]]; then
    echo "Uso: $0 <dev|prod>" >&2
    exit 1
fi

ENV="$1"

if [[ "$ENV" != "dev" && "$ENV" != "prod" ]]; then
    echo "Erro: ambiente deve ser 'dev' ou 'prod'. Recebido: '$ENV'" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Variáveis do ambiente
# ---------------------------------------------------------------------------

REGION="sa-east-1"
ACCOUNT_ID="978594444223"
BUCKET_NAME="provvi-manifests-${ENV}"
TABLE_NAME="provvi-sessions"
FUNCTION_NAME="lambda-signer"
ROLE_NAME="provvi-lambda-role"
ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/${ROLE_NAME}"

# Diretório raiz do projeto Rust da Lambda (relativo a este script)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAMBDA_DIR="${SCRIPT_DIR}/../lambda-signer"

echo ""
echo "=== Iniciando provisionamento Provvi Backend [${ENV}] ==="
echo "Região:    ${REGION}"
echo "Bucket:    ${BUCKET_NAME}"
echo "Tabela:    ${TABLE_NAME}"
echo "Lambda:    ${FUNCTION_NAME}"
echo ""

# ---------------------------------------------------------------------------
# Etapa 1 — IAM Role
# Cria o role de execução da Lambda com permissões para S3 e DynamoDB.
# Políticas gerenciadas usadas por simplicidade — em produção considerar
# políticas de menor privilégio específicas para os recursos do Provvi.
# ---------------------------------------------------------------------------

echo "[1/6] IAM Role: ${ROLE_NAME}"

if aws iam get-role --role-name "${ROLE_NAME}" --region "${REGION}" &>/dev/null; then
    echo "  ✓ Role já existe, pulando criação."
else
    echo "  Criando role..."

    # Trust policy: permite apenas o serviço Lambda assumir este role
    TRUST_POLICY='{
        "Version": "2012-10-17",
        "Statement": [{
            "Effect": "Allow",
            "Principal": { "Service": "lambda.amazonaws.com" },
            "Action": "sts:AssumeRole"
        }]
    }'

    aws iam create-role \
        --role-name "${ROLE_NAME}" \
        --assume-role-policy-document "${TRUST_POLICY}" \
        --region "${REGION}" \
        --output text --query 'Role.RoleId' \
        | xargs -I{} echo "  Role criado: {}"
fi

# Anexa políticas gerenciadas apenas se ainda não estiverem anexadas.
# Lista as políticas atuais e compara antes de tentar anexar.
ATTACHED_POLICIES=$(aws iam list-attached-role-policies \
    --role-name "${ROLE_NAME}" \
    --query 'AttachedPolicies[].PolicyArn' \
    --output text)

for POLICY_ARN in \
    "arn:aws:iam::aws:policy/AmazonS3FullAccess" \
    "arn:aws:iam::aws:policy/AmazonDynamoDBFullAccess" \
    "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
do
    POLICY_NAME=$(basename "${POLICY_ARN}")
    if echo "${ATTACHED_POLICIES}" | grep -q "${POLICY_ARN}"; then
        echo "  ✓ Policy já anexada: ${POLICY_NAME}"
    else
        echo "  Anexando policy: ${POLICY_NAME}"
        aws iam attach-role-policy \
            --role-name "${ROLE_NAME}" \
            --policy-arn "${POLICY_ARN}"
    fi
done

# IAM é eventualmente consistente — aguarda propagação antes de usar o role
echo "  Aguardando propagação do IAM (10s)..."
sleep 10

# Gera e armazena a API Key no Secrets Manager (idempotente)
SECRET_NAME="provvi/api-key/${ENV}"
echo "  Verificando secret: ${SECRET_NAME}"
if aws secretsmanager describe-secret \
        --secret-id "${SECRET_NAME}" \
        --region "${REGION}" &>/dev/null; then
    echo "  ✓ Secret já existe: ${SECRET_NAME}"
    API_KEY=$(aws secretsmanager get-secret-value \
        --secret-id "${SECRET_NAME}" \
        --region "${REGION}" \
        --query 'SecretString' --output text)
else
    echo "  Criando secret ${SECRET_NAME}..."
    API_KEY=$(openssl rand -hex 32)
    aws secretsmanager create-secret \
        --name "${SECRET_NAME}" \
        --description "API Key Provvi SDK - ambiente ${ENV}" \
        --secret-string "${API_KEY}" \
        --region "${REGION}" \
        --output text --query 'ARN' \
        | xargs -I{} echo "  Secret criado: {}"
fi

# ---------------------------------------------------------------------------
# Etapa 2 — S3 Bucket
# Armazena imagens JPEG e manifestos C2PA de cada sessão de vistoria.
# Versionamento ativado para rastreabilidade — importante para auditorias.
# ---------------------------------------------------------------------------

echo ""
echo "[2/6] S3 Bucket: ${BUCKET_NAME}"

if aws s3api head-bucket --bucket "${BUCKET_NAME}" --region "${REGION}" &>/dev/null; then
    echo "  ✓ Bucket já existe, pulando criação."
else
    echo "  Criando bucket..."
    aws s3api create-bucket \
        --bucket "${BUCKET_NAME}" \
        --region "${REGION}" \
        --create-bucket-configuration "LocationConstraint=${REGION}" \
        --output text --query 'Location' \
        | xargs -I{} echo "  Bucket criado: {}"
fi

# Ativa versionamento — necessário para histórico de manifestos por sessão
echo "  Ativando versionamento..."
aws s3api put-bucket-versioning \
    --bucket "${BUCKET_NAME}" \
    --versioning-configuration Status=Enabled \
    --region "${REGION}"
echo "  ✓ Versionamento ativo."

# Bloqueia acesso público — manifestos só são acessíveis via URL presigned
echo "  Ativando block public access..."
aws s3api put-public-access-block \
    --bucket "${BUCKET_NAME}" \
    --public-access-block-configuration \
        "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true" \
    --region "${REGION}"
echo "  ✓ Acesso público bloqueado."

# ---------------------------------------------------------------------------
# Etapa 3 — DynamoDB
# Registra metadados de cada sessão: hash do frame, timestamps, chaves S3.
# Modo on-demand (PAY_PER_REQUEST) — sem necessidade de provisionar capacidade.
# ---------------------------------------------------------------------------

echo ""
echo "[3/6] DynamoDB: ${TABLE_NAME}"

if aws dynamodb describe-table \
    --table-name "${TABLE_NAME}" \
    --region "${REGION}" &>/dev/null; then
    echo "  ✓ Tabela já existe, pulando criação."
else
    echo "  Criando tabela..."
    aws dynamodb create-table \
        --table-name "${TABLE_NAME}" \
        --attribute-definitions \
            "AttributeName=session_id,AttributeType=S" \
            "AttributeName=captured_at,AttributeType=N" \
        --key-schema \
            "AttributeName=session_id,KeyType=HASH" \
            "AttributeName=captured_at,KeyType=RANGE" \
        --billing-mode PAY_PER_REQUEST \
        --region "${REGION}" \
        --output text --query 'TableDescription.TableStatus' \
        | xargs -I{} echo "  Tabela criada, status: {}"

    # Aguarda a tabela ficar ativa antes de prosseguir
    echo "  Aguardando tabela ficar ACTIVE..."
    aws dynamodb wait table-exists \
        --table-name "${TABLE_NAME}" \
        --region "${REGION}"
    echo "  ✓ Tabela ACTIVE."
fi

# ---------------------------------------------------------------------------
# Etapa 4 — Build e Deploy da Lambda
# Compila o binário Rust para ARM64 (Graviton2 — menor custo na AWS)
# e faz deploy via cargo-lambda.
# ---------------------------------------------------------------------------

echo ""
echo "[4/6] Lambda: ${FUNCTION_NAME}"

echo "  Compilando para ARM64..."
(cd "${LAMBDA_DIR}" && cargo lambda build --release --arm64)
echo "  ✓ Build concluído."

echo "  Fazendo deploy..."
(cd "${LAMBDA_DIR}" && cargo lambda deploy \
    --binary-name bootstrap \
    --region "${REGION}" \
    --iam-role "${ROLE_ARN}" \
    --env-var "S3_BUCKET=${BUCKET_NAME}" \
    --env-var "DYNAMODB_TABLE=${TABLE_NAME}" \
    --env-var "API_KEY=${API_KEY}" \
    "${FUNCTION_NAME}")
echo "  ✓ Deploy concluído."

# Aguarda a Lambda ficar Active — necessário antes de criar Function URL
echo "  Aguardando Lambda ficar Active..."
for i in $(seq 1 12); do
    STATUS=$(aws lambda get-function-configuration \
        --function-name "${FUNCTION_NAME}" \
        --region "${REGION}" \
        --query 'State' \
        --output text)
    if [[ "${STATUS}" == "Active" ]]; then
        echo "  ✓ Lambda Active."
        break
    fi
    echo "  Estado: ${STATUS} — aguardando (${i}/12)..."
    sleep 5
done

# ---------------------------------------------------------------------------
# Etapa 5 — Function URL
# Expõe a Lambda via HTTPS sem API Gateway.
# Auth NONE para simplicidade no demo — ver DT-005 para plano de autenticação.
# ---------------------------------------------------------------------------

echo ""
echo "[5/6] Function URL"

FUNCTION_URL=""

if aws lambda get-function-url-config \
    --function-name "${FUNCTION_NAME}" \
    --region "${REGION}" &>/dev/null; then
    echo "  ✓ Function URL já existe."
    FUNCTION_URL=$(aws lambda get-function-url-config \
        --function-name "${FUNCTION_NAME}" \
        --region "${REGION}" \
        --query 'FunctionUrl' \
        --output text)
else
    echo "  Criando Function URL..."
    FUNCTION_URL=$(aws lambda create-function-url-config \
        --function-name "${FUNCTION_NAME}" \
        --auth-type NONE \
        --cors '{
            "AllowOrigins": ["*"],
            "AllowMethods": ["POST"],
            "AllowHeaders": ["Content-Type"]
        }' \
        --region "${REGION}" \
        --query 'FunctionUrl' \
        --output text)
    echo "  ✓ Function URL criada: ${FUNCTION_URL}"
fi

# Verifica e cria os statements IAM necessários para invocação pública.
# Cada statement é verificado individualmente para idempotência.
POLICY_JSON=$(aws lambda get-policy \
    --function-name "${FUNCTION_NAME}" \
    --region "${REGION}" \
    --query 'Policy' \
    --output text 2>/dev/null || echo "{}")

# Statement 1: permite invocação pública da função
if echo "${POLICY_JSON}" | grep -q "AllowPublicInvoke"; then
    echo "  ✓ Statement AllowPublicInvoke já existe."
else
    echo "  Criando statement AllowPublicInvoke..."
    aws lambda add-permission \
        --function-name "${FUNCTION_NAME}" \
        --statement-id "AllowPublicInvoke" \
        --action "lambda:InvokeFunction" \
        --principal "*" \
        --region "${REGION}" \
        --output text --query 'Statement' >/dev/null
    echo "  ✓ Statement AllowPublicInvoke criado."
fi

# Statement 2: permite invocação via Function URL sem autenticação
if echo "${POLICY_JSON}" | grep -q "AllowPublicFunctionUrl"; then
    echo "  ✓ Statement AllowPublicFunctionUrl já existe."
else
    echo "  Criando statement AllowPublicFunctionUrl..."
    aws lambda add-permission \
        --function-name "${FUNCTION_NAME}" \
        --statement-id "AllowPublicFunctionUrl" \
        --action "lambda:InvokeFunctionUrl" \
        --principal "*" \
        --function-url-auth-type NONE \
        --region "${REGION}" \
        --output text --query 'Statement' >/dev/null
    echo "  ✓ Statement AllowPublicFunctionUrl criado."
fi

# ---------------------------------------------------------------------------
# Etapa 6 — Validação end-to-end
# Invoca a Lambda via curl com payload mínimo e verifica resposta esperada.
# ---------------------------------------------------------------------------

echo ""
echo "[6/6] Validação"

VALIDATION_SESSION="provision-test-${ENV}-$(date +%s)"
echo "  Invocando Lambda com session_id: ${VALIDATION_SESSION}"

RESPONSE=$(curl -s -X POST "${FUNCTION_URL}" \
    -H "Content-Type: application/json" \
    -d "{
        \"session_id\":\"${VALIDATION_SESSION}\",
        \"image_base64\":\"dGVzdA==\",
        \"manifest_json\":\"{}\",
        \"frame_hash_hex\":\"provision-check\",
        \"captured_at_nanos\":$(date +%s%N),
        \"assertions\":{}
    }")

if echo "${RESPONSE}" | grep -q '"status":"stored"'; then
    echo "  ✓ Validação bem-sucedida — sessão armazenada no backend."
else
    echo "  ✗ Validação falhou. Resposta:" >&2
    echo "    ${RESPONSE}" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Resumo final
# ---------------------------------------------------------------------------

echo ""
echo "=== Provvi Backend [${ENV}] provisionado ==="
echo "Região:       ${REGION}"
echo "S3 Bucket:    ${BUCKET_NAME}"
echo "DynamoDB:     ${TABLE_NAME}"
echo "Lambda:       ${FUNCTION_NAME}"
echo "Function URL: ${FUNCTION_URL}"
echo ""
