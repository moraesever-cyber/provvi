using Android.Content;
using Android.Runtime;

namespace Provvi.Maui;

// ---------------------------------------------------------------------------
// Resultado da captura Provvi
// ---------------------------------------------------------------------------

/// <summary>
/// Resultado de uma captura autenticada pelo SDK Provvi.
/// Mapeado a partir do CaptureOutcome.Success do SDK Kotlin.
/// </summary>
public sealed class ProvviCaptureResult
{
    public string SessionId { get; init; } = "";
    public string ManifestJson { get; init; } = "";
    public string FrameHashHex { get; init; } = "";
    public bool LocationSuspicious { get; init; }
    /// <summary>Timestamp da captura em milissegundos desde Unix epoch (System.currentTimeMillis()).</summary>
    public long CapturedAtMs { get; init; }
    public bool HasIntegrityToken { get; init; }
    public string ManifestUrl { get; init; } = "";
    /// <summary>true se a deriva entre CapturedAtMs e o relógio no momento da consolidação for superior a 300 s.</summary>
    public bool ClockSuspicious { get; init; }
    /// <summary>"NONE" | "MEDIUM" | "HIGH" — risco consolidado de recaptura.</summary>
    public string IntegrityRisk { get; init; } = "NONE";
    public IReadOnlyDictionary<string, long> PipelineTimingsMs { get; init; }
        = new Dictionary<string, long>();
}

/// <summary>
/// Erros possíveis retornados pelo SDK (DT-019).
/// Mapeados a partir de ProvviErrorType do SDK Kotlin.
/// Os valores originais são preservados para compatibilidade com código existente.
/// </summary>
public enum ProvviErrorCode
{
    // --- Originais (mantidos para compatibilidade) ---
    PermissionDenied,
    DeviceCompromised,
    MockLocationDetected,
    RecaptureSuspected,
    SigningFailed,
    CaptureError,
    Unknown,

    // --- Novos em DT-019 ---
    /// <summary>Strongbox não disponível no hardware — não bloqueante.</summary>
    StrongboxUnavailable,
    /// <summary>Play Integrity API inacessível.</summary>
    AttestationError,
    /// <summary>Câmera física não encontrada.</summary>
    CameraNotFound,
    /// <summary>Câmera em uso exclusivo por outro processo.</summary>
    CameraInUse,
    /// <summary>Timeout: câmera não entregou frame em 10 s.</summary>
    CameraTimeout,
    /// <summary>Nenhuma fonte de localização respondeu.</summary>
    LocationUnavailable,
    /// <summary>Manifesto C2PA inválido ou não parseável.</summary>
    ManifestInvalid,
    /// <summary>Falha no cálculo do hash SHA-256 do frame.</summary>
    FrameHashFailed,
    /// <summary>Sem conexão de rede no momento da captura.</summary>
    NetworkUnavailable,
    /// <summary>Upload ao backend falhou após retentativas.</summary>
    BackendUnavailable,
    /// <summary>Backend retornou 401 — API Key inválida ou expirada.</summary>
    BackendAuthFailed,
    /// <summary>Timeout no upload ao backend.</summary>
    BackendTimeout,
    /// <summary>TSA inacessível após 3 tentativas com backoff.</summary>
    TsaUnavailable,
    /// <summary>Deriva de relógio superior a 300 s — possível manipulação.</summary>
    ClockSuspicious,
}

/// <summary>
/// Resultado de uma captura Provvi sem lançamento de exceções (DT-019).
///
/// Use com pattern matching:
/// <code>
/// var outcome = await capture.CaptureResultAsync("VHC-001", "App");
/// if (outcome is ProvviCaptureOutcome.Success ok)
///     Console.WriteLine(ok.Result.SessionId);
/// else if (outcome is ProvviCaptureOutcome.Failure fail)
///     Console.WriteLine($"Erro: {fail.Code} — {fail.Message}");
/// </code>
/// </summary>
public abstract record ProvviCaptureOutcome
{
    /// <summary>Captura concluída com sucesso.</summary>
    public sealed record Success(ProvviCaptureResult Result) : ProvviCaptureOutcome;

    /// <summary>Captura falhou com código de erro classificado.</summary>
    public sealed record Failure(ProvviErrorCode Code, string Message) : ProvviCaptureOutcome;
}

/// <summary>
/// Exceção lançada quando a captura falha.
/// </summary>
public sealed class ProvviCaptureException(ProvviErrorCode code, string message)
    : Exception(message)
{
    public ProvviErrorCode ErrorCode { get; } = code;
}

// ---------------------------------------------------------------------------
// Tipos específicos do domínio HabilitAi
// ---------------------------------------------------------------------------

/// <summary>
/// Eventos registráveis pelo HabilitAi em cada captura.
/// Mapeado ao HabilitAiEvent.kt do SDK Android.
/// </summary>
public enum HabilitAiEvent
{
    StudentStartBiometry,
    InstructorStartBiometry,
    StudentEndBiometry,
    InstructorEndBiometry,
    OdometerStart,
    OdometerEnd
}

/// <summary>
/// Parâmetros de uma captura HabilitAi.
/// </summary>
public sealed class HabilitAiCaptureRequest
{
    public string ClassId  { get; init; } = "";
    public string DeviceId { get; init; } = "";
    public HabilitAiEvent Event { get; init; }
}

// ---------------------------------------------------------------------------
// Cliente de captura — bridge C# → Kotlin via JNI
// ---------------------------------------------------------------------------

/// <summary>
/// Wrapper .NET/MAUI para o SDK Provvi Android.
///
/// Usa invocação JNI direta via Android.Runtime.JNIEnv para chamar o
/// SDK Kotlin sem gerar bindings automáticos (que requerem fonte Java/Kotlin).
///
/// Padrão de uso:
/// <code>
///   var capture = new ProvviCapture(context, lambdaUrl, apiKey);
///   var result = await capture.CaptureAsync(referenceId: "VHC-001", capturedBy: "App");
///   Console.WriteLine(result.SessionId);
/// </code>
/// </summary>
public sealed class ProvviCapture : IDisposable
{
    // Nomes JNI completos das classes e interfaces Kotlin do SDK
    private const string ProvviCaptureClass      = "br/com/provvi/ProvviCapture";
    private const string BackendConfigClass      = "br/com/provvi/backend/BackendConfig";
    private const string BackendClientClass      = "br/com/provvi/backend/ProvviBackendClient";
    private const string GenericAssertionsClass  = "br/com/provvi/assertions/GenericAssertions";
    private const string HabilitAiAssertionsClass = "br/com/provvi/assertions/HabilitAiAssertions";
    private const string HabilitAiEventClass     = "br/com/provvi/assertions/HabilitAiEvent";
    // Interface usada como tipo do parâmetro em captureBlocking()
    private const string ProvviAssertionsIface   = "br/com/provvi/assertions/ProvviAssertions";

    private readonly Context _context;
    private readonly string _lambdaUrl;
    private readonly string _apiKey;
    private bool _disposed;

    /// <param name="context">Context Android (Activity ou Application).</param>
    /// <param name="lambdaUrl">URL da Lambda de assinatura Provvi.</param>
    /// <param name="apiKey">API Key configurada no backend. Vazio desativa autenticação.</param>
    public ProvviCapture(Context context, string lambdaUrl, string apiKey = "")
    {
        _context   = context ?? throw new ArgumentNullException(nameof(context));
        _lambdaUrl = lambdaUrl ?? throw new ArgumentNullException(nameof(lambdaUrl));
        _apiKey    = apiKey;
    }

    /// <summary>
    /// Executa o pipeline completo de captura autenticada com asserções genéricas.
    ///
    /// Lança <see cref="ProvviCaptureException"/> para todos os erros do SDK.
    /// </summary>
    /// <param name="referenceId">Identificador do objeto vistoriado (veículo, imóvel, etc.).</param>
    /// <param name="capturedBy">Nome do operador ou sistema que iniciou a captura.</param>
    /// <param name="cancellationToken">Token para cancelamento (não propaga ao SDK Kotlin).</param>
    public Task<ProvviCaptureResult> CaptureAsync(
        string referenceId,
        string capturedBy,
        CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        return Task.Run(() => ExecuteCapture(referenceId, capturedBy), cancellationToken);
    }

    /// <summary>
    /// Captura autenticada com asserções HabilitAi.
    /// Use este método no app HabilitAi em vez de CaptureAsync().
    /// </summary>
    public Task<ProvviCaptureResult> CaptureHabilitAiAsync(
        HabilitAiCaptureRequest request,
        CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        return Task.Run(() => ExecuteHabilitAiCapture(request), cancellationToken);
    }

    /// <summary>
    /// Executa o pipeline completo de captura autenticada sem lançar exceções (DT-019).
    ///
    /// Retorna <see cref="ProvviCaptureOutcome.Success"/> em caso de sucesso ou
    /// <see cref="ProvviCaptureOutcome.Failure"/> em qualquer falha classificada.
    /// Prefira este método em código novo — <see cref="CaptureAsync"/> permanece
    /// disponível para compatibilidade com código existente.
    /// </summary>
    /// <param name="referenceId">Identificador do objeto vistoriado.</param>
    /// <param name="capturedBy">Nome do operador ou sistema que iniciou a captura.</param>
    /// <param name="cancellationToken">Token para cancelamento (não propaga ao SDK Kotlin).</param>
    public Task<ProvviCaptureOutcome> CaptureResultAsync(
        string referenceId,
        string capturedBy,
        CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        return Task.Run(() => ExecuteCaptureResult(referenceId, capturedBy), cancellationToken);
    }

    /// <summary>
    /// Versão sem exceções da captura HabilitAi (DT-019).
    /// </summary>
    public Task<ProvviCaptureOutcome> CaptureHabilitAiResultAsync(
        HabilitAiCaptureRequest request,
        CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        return Task.Run(() => ExecuteHabilitAiCaptureResult(request), cancellationToken);
    }

    public void Dispose()
    {
        _disposed = true;
    }

    // ---------------------------------------------------------------------------
    // Implementações sem exceções (DT-019)
    // ---------------------------------------------------------------------------

    private ProvviCaptureOutcome ExecuteCaptureResult(string referenceId, string capturedBy)
    {
        try
        {
            return new ProvviCaptureOutcome.Success(ExecuteCapture(referenceId, capturedBy));
        }
        catch (ProvviCaptureException ex)
        {
            return new ProvviCaptureOutcome.Failure(ex.ErrorCode, ex.Message);
        }
        catch (Exception ex)
        {
            return new ProvviCaptureOutcome.Failure(ProvviErrorCode.Unknown,
                $"Erro inesperado: {ex.Message}");
        }
    }

    private ProvviCaptureOutcome ExecuteHabilitAiCaptureResult(HabilitAiCaptureRequest request)
    {
        try
        {
            return new ProvviCaptureOutcome.Success(ExecuteHabilitAiCapture(request));
        }
        catch (ProvviCaptureException ex)
        {
            return new ProvviCaptureOutcome.Failure(ex.ErrorCode, ex.Message);
        }
        catch (Exception ex)
        {
            return new ProvviCaptureOutcome.Failure(ProvviErrorCode.Unknown,
                $"Erro inesperado: {ex.Message}");
        }
    }

    // ---------------------------------------------------------------------------
    // Invocação JNI — métodos de entrada por tipo de asserção
    // ---------------------------------------------------------------------------

    private ProvviCaptureResult ExecuteCapture(string referenceId, string capturedBy)
    {
        var env = JNIEnv.AttachCurrentThread();
        try
        {
            // Cria GenericAssertions(capturedBy, referenceId)
            var assertionsClass = JNIEnv.FindClass(GenericAssertionsClass);
            var assertionsCtor  = JNIEnv.GetMethodID(assertionsClass, "<init>",
                "(Ljava/lang/String;Ljava/lang/String;)V");
            var capturedByJava  = new Java.Lang.String(capturedBy);
            var refIdJava       = new Java.Lang.String(referenceId);
            var assertionsObj   = JNIEnv.NewObject(assertionsClass, assertionsCtor,
                new JValue[] { new(capturedByJava.Handle), new(refIdJava.Handle) });

            return ExecuteCaptureWithAssertions(assertionsObj);
        }
        finally
        {
            JNIEnv.DetachCurrentThread();
        }
    }

    private ProvviCaptureResult ExecuteHabilitAiCapture(HabilitAiCaptureRequest request)
    {
        var env = JNIEnv.AttachCurrentThread();
        try
        {
            // Mapeia o enum C# para o enum Kotlin via JNI
            var eventClass    = JNIEnv.FindClass(HabilitAiEventClass);
            var eventCode     = MapHabilitAiEvent(request.Event);
            var valuesMethod  = JNIEnv.GetStaticMethodID(eventClass, "valueOf",
                $"(Ljava/lang/String;)L{HabilitAiEventClass};");
            var eventCodeJava = new Java.Lang.String(eventCode);
            var eventObj      = JNIEnv.CallStaticObjectMethod(eventClass, valuesMethod,
                new JValue[] { new(eventCodeJava.Handle) });

            // Cria HabilitAiAssertions(classId, deviceId, event)
            var assertionsClass = JNIEnv.FindClass(HabilitAiAssertionsClass);
            var assertionsCtor  = JNIEnv.GetMethodID(assertionsClass, "<init>",
                $"(Ljava/lang/String;Ljava/lang/String;L{HabilitAiEventClass};)V");
            var classIdJava  = new Java.Lang.String(request.ClassId);
            var deviceIdJava = new Java.Lang.String(request.DeviceId);
            var assertionsObj = JNIEnv.NewObject(assertionsClass, assertionsCtor,
                new JValue[]
                {
                    new(classIdJava.Handle),
                    new(deviceIdJava.Handle),
                    new(eventObj)
                });

            return ExecuteCaptureWithAssertions(assertionsObj);
        }
        finally
        {
            JNIEnv.DetachCurrentThread();
        }
    }

    // ---------------------------------------------------------------------------
    // Núcleo compartilhado — backend + SDK invocation + parse
    // Assume que JNI já está anexado à thread corrente.
    // ---------------------------------------------------------------------------

    /// <summary>
    /// Cria BackendClient, invoca captureBlocking() com as asserções fornecidas
    /// e interpreta o CaptureOutcome retornado.
    ///
    /// Chamado após a criação das asserções específicas do domínio —
    /// não deve ser chamado fora dos métodos Execute* (JNI deve estar anexado).
    /// </summary>
    private ProvviCaptureResult ExecuteCaptureWithAssertions(IntPtr assertionsObj)
    {
        // 1. Cria BackendConfig(lambdaUrl, timeoutSeconds=30, apiKey)
        var configClass = JNIEnv.FindClass(BackendConfigClass);
        var configCtor  = JNIEnv.GetMethodID(configClass, "<init>",
            "(Ljava/lang/String;JLjava/lang/String;)V");
        var lambdaUrlJava = new Java.Lang.String(_lambdaUrl);
        var apiKeyJava    = new Java.Lang.String(_apiKey);
        var configObj     = JNIEnv.NewObject(configClass, configCtor,
            new JValue[]
            {
                new(lambdaUrlJava.Handle),
                new(30L),
                new(apiKeyJava.Handle)
            });

        // 2. Cria ProvviBackendClient(config)
        var clientClass = JNIEnv.FindClass(BackendClientClass);
        var clientCtor  = JNIEnv.GetMethodID(clientClass, "<init>",
            $"(L{BackendConfigClass};)V");
        var clientObj   = JNIEnv.NewObject(clientClass, clientCtor,
            new JValue[] { new(configObj) });

        // 3. Cria br.com.provvi.ProvviCapture(context)
        var captureClass = JNIEnv.FindClass(ProvviCaptureClass);
        var captureCtor  = JNIEnv.GetMethodID(captureClass, "<init>",
            "(Landroid/content/Context;)V");
        var captureObj   = JNIEnv.NewObject(captureClass, captureCtor,
            new JValue[] { new(_context.Handle) });

        // 4. Obtém ProcessLifecycleOwner como LifecycleOwner padrão
        var lifecycleClass = JNIEnv.FindClass("androidx/lifecycle/ProcessLifecycleOwner");
        var getMethod      = JNIEnv.GetStaticMethodID(lifecycleClass, "get",
            "()Landroidx/lifecycle/ProcessLifecycleOwner;");
        var lifecycleObj   = JNIEnv.CallStaticObjectMethod(lifecycleClass, getMethod);

        // 5. Invoca captureBlocking(lifecycleOwner, assertions, backendClient)
        //    O parâmetro de assertions usa a interface ProvviAssertions no descritor JNI
        var captureMethod = JNIEnv.GetMethodID(captureClass, "captureBlocking",
            $"(Landroidx/lifecycle/LifecycleOwner;L{ProvviAssertionsIface};L{BackendClientClass};)Ljava/lang/Object;");
        var outcomeObj = JNIEnv.CallObjectMethod(captureObj, captureMethod,
            new JValue[]
            {
                new(lifecycleObj),
                new(assertionsObj),
                new(clientObj)
            });

        return ParseOutcome(outcomeObj);
    }

    // ---------------------------------------------------------------------------
    // Parsing do resultado e helpers estáticos
    // ---------------------------------------------------------------------------

    /// <summary>
    /// Interpreta o CaptureOutcome retornado pelo SDK Kotlin.
    /// Mapeia Success → ProvviCaptureResult e erros → ProvviCaptureException.
    /// </summary>
    private static ProvviCaptureResult ParseOutcome(IntPtr outcomeHandle)
    {
        if (outcomeHandle == IntPtr.Zero)
            throw new ProvviCaptureException(ProvviErrorCode.Unknown, "SDK retornou null");

        var outcomeClass = JNIEnv.GetObjectClass(outcomeHandle);
        var classNameId  = JNIEnv.GetMethodID(
            JNIEnv.FindClass("java/lang/Class"), "getName", "()Ljava/lang/String;");
        var classNameObj = JNIEnv.CallObjectMethod(outcomeClass, classNameId);
        var className    = JNIEnv.GetString(classNameObj, JniHandleOwnership.TransferLocalRef);

        return className switch
        {
            "br.com.provvi.CaptureOutcome$Success"              => ExtractSuccess(outcomeHandle),
            "br.com.provvi.CaptureOutcome$PermissionDenied"     => throw new ProvviCaptureException(
                ProvviErrorCode.PermissionDenied, "Permissão negada"),
            "br.com.provvi.CaptureOutcome$DeviceCompromised"    => throw new ProvviCaptureException(
                ProvviErrorCode.DeviceCompromised, "Dispositivo comprometido"),
            "br.com.provvi.CaptureOutcome$MockLocationDetected" => throw new ProvviCaptureException(
                ProvviErrorCode.MockLocationDetected, "Localização simulada detectada"),
            "br.com.provvi.CaptureOutcome$RecaptureSuspected"   => throw new ProvviCaptureException(
                ProvviErrorCode.RecaptureSuspected, ExtractErrorReason(outcomeHandle, "getReason")),
            "br.com.provvi.CaptureOutcome$SigningFailed"        => throw new ProvviCaptureException(
                ProvviErrorCode.SigningFailed, ExtractErrorReason(outcomeHandle, "getReason")),
            "br.com.provvi.CaptureOutcome$CaptureError"         => throw new ProvviCaptureException(
                ProvviErrorCode.CaptureError, ExtractErrorReason(outcomeHandle, "getReason")),
            _ => throw new ProvviCaptureException(ProvviErrorCode.Unknown,
                $"CaptureOutcome desconhecido: {className}")
        };
    }

    private static ProvviCaptureResult ExtractSuccess(IntPtr outcomeHandle)
    {
        var sessionClass = JNIEnv.FindClass("br/com/provvi/CaptureSession");

        var sessionProp = JNIEnv.GetMethodID(
            JNIEnv.GetObjectClass(outcomeHandle), "getSession",
            "()Lbr/com/provvi/CaptureSession;");
        var session = JNIEnv.CallObjectMethod(outcomeHandle, sessionProp);

        string GetString(string method) {
            var id  = JNIEnv.GetMethodID(sessionClass, method, "()Ljava/lang/String;");
            var obj = JNIEnv.CallObjectMethod(session, id);
            return JNIEnv.GetString(obj, JniHandleOwnership.TransferLocalRef) ?? "";
        }

        long GetLong(string method) {
            var id = JNIEnv.GetMethodID(sessionClass, method, "()J");
            return JNIEnv.CallLongMethod(session, id);
        }

        bool GetBool(string method) {
            var id = JNIEnv.GetMethodID(sessionClass, method, "()Z");
            return JNIEnv.CallBooleanMethod(session, id);
        }

        // getDeviceIntegrityToken retorna String (vazia quando ausente) — HasIntegrityToken é derivado
        var tokenStr = GetString("getDeviceIntegrityToken");

        return new ProvviCaptureResult
        {
            SessionId          = GetString("getSessionId"),
            ManifestJson       = GetString("getManifestJson"),
            FrameHashHex       = GetString("getFrameHashHex"),
            LocationSuspicious = GetBool("getLocationSuspicious"),
            CapturedAtMs       = GetLong("getCapturedAtMs"),
            HasIntegrityToken  = !string.IsNullOrEmpty(tokenStr),
            ManifestUrl        = GetString("getManifestUrl"),
            ClockSuspicious    = GetBool("getClockSuspicious"),
            IntegrityRisk      = GetString("getIntegrityRisk"),
            PipelineTimingsMs  = ExtractTimings(session, sessionClass),
        };
    }

    private static IReadOnlyDictionary<string, long> ExtractTimings(
        IntPtr session, IntPtr sessionClass)
    {
        try
        {
            var mapMethod = JNIEnv.GetMethodID(sessionClass,
                "getPipelineTimingsMs", "()Ljava/util/Map;");
            var mapObj = JNIEnv.CallObjectMethod(session, mapMethod);
            if (mapObj == IntPtr.Zero) return new Dictionary<string, long>();

            // Itera entrySet() do Map Java
            var mapClass   = JNIEnv.FindClass("java/util/Map");
            var entrySetId = JNIEnv.GetMethodID(mapClass, "entrySet", "()Ljava/util/Set;");
            var entrySet   = JNIEnv.CallObjectMethod(mapObj, entrySetId);

            var setClass   = JNIEnv.FindClass("java/util/Set");
            var iteratorId = JNIEnv.GetMethodID(setClass, "iterator", "()Ljava/util/Iterator;");
            var iterator   = JNIEnv.CallObjectMethod(entrySet, iteratorId);

            var iterClass  = JNIEnv.FindClass("java/util/Iterator");
            var hasNextId  = JNIEnv.GetMethodID(iterClass, "hasNext", "()Z");
            var nextId     = JNIEnv.GetMethodID(iterClass, "next", "()Ljava/lang/Object;");

            var entryClass = JNIEnv.FindClass("java/util/Map$Entry");
            var getKeyId   = JNIEnv.GetMethodID(entryClass, "getKey", "()Ljava/lang/Object;");
            var getValueId = JNIEnv.GetMethodID(entryClass, "getValue", "()Ljava/lang/Object;");

            var longClass  = JNIEnv.FindClass("java/lang/Long");
            var longValueId = JNIEnv.GetMethodID(longClass, "longValue", "()J");

            var result = new Dictionary<string, long>();
            while (JNIEnv.CallBooleanMethod(iterator, hasNextId))
            {
                var entry  = JNIEnv.CallObjectMethod(iterator, nextId);
                var keyObj = JNIEnv.CallObjectMethod(entry, getKeyId);
                var valObj = JNIEnv.CallObjectMethod(entry, getValueId);

                var key = JNIEnv.GetString(keyObj, JniHandleOwnership.TransferLocalRef) ?? "";
                var val = JNIEnv.CallLongMethod(valObj, longValueId);
                result[key] = val;
            }
            return result;
        }
        catch
        {
            return new Dictionary<string, long>();
        }
    }

    private static string ExtractErrorReason(IntPtr handle, string method)
    {
        try
        {
            var cls = JNIEnv.GetObjectClass(handle);
            var id  = JNIEnv.GetMethodID(cls, method, "()Ljava/lang/String;");
            var obj = JNIEnv.CallObjectMethod(handle, id);
            return JNIEnv.GetString(obj, JniHandleOwnership.TransferLocalRef) ?? "Erro desconhecido";
        }
        catch
        {
            return "Erro desconhecido";
        }
    }

    /// <summary>
    /// Mapeia HabilitAiEvent C# para o nome do enum Kotlin (usado em valueOf()).
    /// </summary>
    private static string MapHabilitAiEvent(HabilitAiEvent evt) => evt switch
    {
        HabilitAiEvent.StudentStartBiometry    => "STUDENT_START_BIOMETRY",
        HabilitAiEvent.InstructorStartBiometry => "INSTRUCTOR_START_BIOMETRY",
        HabilitAiEvent.StudentEndBiometry      => "STUDENT_END_BIOMETRY",
        HabilitAiEvent.InstructorEndBiometry   => "INSTRUCTOR_END_BIOMETRY",
        HabilitAiEvent.OdometerStart           => "ODOMETER_START",
        HabilitAiEvent.OdometerEnd             => "ODOMETER_END",
        _ => throw new ArgumentOutOfRangeException(nameof(evt), evt, null)
    };
}
