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
    public long CapturedAtNanos { get; init; }
    public bool HasIntegrityToken { get; init; }
    public string ManifestUrl { get; init; } = "";
    public IReadOnlyDictionary<string, long> PipelineTimingsMs { get; init; }
        = new Dictionary<string, long>();
}

/// <summary>
/// Erros possíveis retornados pelo SDK.
/// Mapeados a partir dos subtipos de CaptureOutcome do Kotlin.
/// </summary>
public enum ProvviErrorCode
{
    PermissionDenied,
    DeviceCompromised,
    MockLocationDetected,
    RecaptureSuspected,
    SigningFailed,
    CaptureError,
    Unknown
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

    public void Dispose()
    {
        _disposed = true;
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

        return new ProvviCaptureResult
        {
            SessionId          = GetString("getSessionId"),
            ManifestJson       = GetString("getManifestJson"),
            FrameHashHex       = GetString("getFrameHashHex"),
            LocationSuspicious = GetBool("getLocationSuspicious"),
            CapturedAtNanos    = GetLong("getCapturedAtNanos"),
            ManifestUrl        = GetString("getManifestUrl"),
        };
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
