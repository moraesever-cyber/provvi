#!/usr/bin/env bash
# deploy-verifier.sh — Build e deploy do lambda-verifier na AWS
#
# Uso: ./deploy-verifier.sh
#
# Pré-requisitos: cargo-lambda instalado, AWS CLI configurado, credenciais ativas.

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuração
# ---------------------------------------------------------------------------

REGION="sa-east-1"
ACCOUNT_ID="978594444223"
FUNCTION_NAME="lambda-verifier"
ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/provvi-lambda-role"

# URL do próprio verifier — usada no rodapé do certificado HTML.
# Hardcoded porque a infraestrutura já existe e a URL não muda.
VERIFIER_URL="https://ldp4x25jnk2mrppz75ts2kiq7a0bylxw.lambda-url.sa-east-1.on.aws/"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAMBDA_DIR="${SCRIPT_DIR}/../lambda-verifier"

echo ""
echo "=== Deploy lambda-verifier ==="
echo "Região:   ${REGION}"
echo "Função:   ${FUNCTION_NAME}"
echo ""

# ---------------------------------------------------------------------------
# Etapa 1 — Build para ARM64 (Graviton2)
# ---------------------------------------------------------------------------

echo "[1/3] Compilando para ARM64..."
(cd "${LAMBDA_DIR}" && cargo lambda build --release --arm64)
echo "  ✓ Build concluído."

# ---------------------------------------------------------------------------
# Etapa 2 — Deploy
# ---------------------------------------------------------------------------

echo ""
echo "[2/3] Fazendo deploy..."

(cd "${LAMBDA_DIR}" && cargo lambda deploy \
    --binary-name bootstrap \
    --region "${REGION}" \
    --iam-role "${ROLE_ARN}" \
    --env-var "VERIFIER_BASE_URL=${VERIFIER_URL}" \
    "${FUNCTION_NAME}")

echo "  ✓ Deploy concluído."

# ---------------------------------------------------------------------------
# Etapa 3 — Validação: abre a URL no browser para confirmar resposta HTML
# ---------------------------------------------------------------------------

echo ""
echo "[3/3] Validação"

HEALTH_URL="${VERIFIER_URL}?session_id=deploy-check-$(date +%s)"
echo "  URL de teste: ${HEALTH_URL}"

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "Accept: text/html" \
    "${HEALTH_URL}")

if [[ "${HTTP_STATUS}" == "200" ]]; then
    echo "  ✓ Verifier respondendo com HTTP ${HTTP_STATUS}."
else
    echo "  ✗ Resposta inesperada: HTTP ${HTTP_STATUS}" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Resumo
# ---------------------------------------------------------------------------

echo ""
echo "=== Deploy concluído ==="
echo "Função:       ${FUNCTION_NAME}"
echo "URL pública:  ${VERIFIER_URL}"
echo ""
echo "Teste manual — abra no browser (deve exibir página HTML de sessão não encontrada):"
echo "  ${VERIFIER_URL}?session_id=teste"
echo ""
