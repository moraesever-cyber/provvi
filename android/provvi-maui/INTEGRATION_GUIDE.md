# Provvi SDK — Guia de Integração MAUI Android
**Versão:** 1.0 | **Data:** 2026-03-06 | **Para:** Dev HabilitAi

## Pré-requisitos

- .NET 8 com workload Android instalado
- Android minSdk 26 (Android 8.0+)
- Permissões no AndroidManifest.xml do app HabilitAi

## 1. Configuração do Projeto

### 1.1 Adicione referência ao wrapper

No `.csproj` do app HabilitAi:
```xml
<ProjectReference Include="path/to/provvi-maui/provvi-maui.csproj" />
```

### 1.2 Adicione permissões no AndroidManifest.xml
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.INTERNET" />
```

### 1.3 Registre o SDK no MauiProgram.cs
```csharp
builder.Services.AddProvvi(options =>
{
    options.LambdaUrl = "https://3nw6hxeumaqhtkrtghjtkzyamq0sojrk.lambda-url.sa-east-1.on.aws/";
    options.ApiKey    = "SOLICITAR_AO_PROVVI";
});
```

## 2. Uso nas ViewModels

### 2.1 Injeção de dependência
```csharp
public class AulaViewModel
{
    private readonly ProvviCapture _capture;

    public AulaViewModel(ProvviCapture capture)
    {
        _capture = capture;
    }
}
```

### 2.2 Captura de biometria do aluno (início)
```csharp
private async Task CapturarBiometriaInicioAlunoAsync(string aulaId)
{
    try
    {
        var result = await _capture.CaptureHabilitAiAsync(
            new HabilitAiCaptureRequest
            {
                ClassId  = aulaId,
                DeviceId = ObterDeviceId(),
                Event    = HabilitAiEvent.StudentStartBiometry
            });

        // Armazenar no Supabase — apenas session_id e manifest_url
        await _supabase
            .From<RegistroPresenca>()
            .Insert(new RegistroPresenca
            {
                AulaId      = aulaId,
                SessionId   = result.SessionId,
                ManifestUrl = result.ManifestUrl,
                FrameHash   = result.FrameHashHex,
                Evento      = "student_start_biometry",
                CapturedAt  = DateTimeOffset.FromUnixTimeMilliseconds(
                                  result.CapturedAtNanos / 1_000_000)
            });
    }
    catch (ProvviCaptureException ex)
    {
        await TratarErroCapturaAsync(ex);
    }
}
```

### 2.3 Tabela de eventos disponíveis

| Enum C#                        | Evento                           |
|--------------------------------|----------------------------------|
| `StudentStartBiometry`         | Biometria de início do aluno     |
| `InstructorStartBiometry`      | Biometria de início do instrutor |
| `StudentEndBiometry`           | Biometria de fim do aluno        |
| `InstructorEndBiometry`        | Biometria de fim do instrutor    |
| `OdometerStart`                | Odômetro início                  |
| `OdometerEnd`                  | Odômetro fim                     |

### 2.4 Tratamento de erros
```csharp
private async Task TratarErroCapturaAsync(ProvviCaptureException ex)
{
    var mensagem = ex.ErrorCode switch
    {
        ProvviErrorCode.PermissionDenied      =>
            "Permissão negada. Acesse Configurações e habilite câmera e localização.",
        ProvviErrorCode.DeviceCompromised     =>
            "Dispositivo comprometido. Captura bloqueada por segurança.",
        ProvviErrorCode.MockLocationDetected  =>
            "Localização simulada detectada. Desative apps de GPS falso.",
        ProvviErrorCode.RecaptureSuspected    =>
            "Possível fraude detectada. Fotografe o aluno/odômetro diretamente.",
        ProvviErrorCode.SigningFailed         =>
            "Falha na assinatura. Tente novamente.",
        ProvviErrorCode.CaptureError          =>
            $"Erro na captura: {ex.Message}",
        _                                     =>
            $"Erro inesperado: {ex.Message}"
    };

    await Shell.Current.DisplayAlert("Erro na Captura", mensagem, "OK");
}
```

### 2.5 Obter Device ID
```csharp
private string ObterDeviceId()
{
    return Android.Provider.Settings.Secure.GetString(
               Platform.CurrentActivity?.ContentResolver,
               Android.Provider.Settings.Secure.AndroidId)
           ?? "unknown";
}
```

## 3. Fluxo completo de uma aula
```
1. Instrutor abre a aula no app
2. App chama CaptureHabilitAiAsync(InstructorStartBiometry)
3. SDK captura foto do instrutor + GPS + integridade
4. App salva session_id no Supabase

5. Aluno realiza prova prática
6. App chama CaptureHabilitAiAsync(StudentStartBiometry)

7. Ao fim: OdometerStart + OdometerEnd
8. App chama CaptureHabilitAiAsync(InstructorEndBiometry)
9. App chama CaptureHabilitAiAsync(StudentEndBiometry)

Cada captura gera:
- session_id único armazenado no Supabase (HabilitAi)
- imagem + manifesto C2PA armazenados no S3 (Provvi)
- registro no DynamoDB (Provvi)
```

## 4. Campos retornados em ProvviCaptureResult

| Campo                | Tipo                       | Descrição                           |
|----------------------|----------------------------|-------------------------------------|
| `SessionId`          | `string`                   | UUID único da captura               |
| `ManifestJson`       | `string`                   | Manifesto C2PA completo             |
| `FrameHashHex`       | `string`                   | SHA-256 do frame original           |
| `ManifestUrl`        | `string`                   | URL S3 presigned (válida 7 dias)    |
| `LocationSuspicious` | `bool`                     | GPS divergente ou mock detectado    |
| `HasIntegrityToken`  | `bool`                     | Play Integrity disponível           |
| `CapturedAtNanos`    | `long`                     | Timestamp do sensor (nanosegundos)  |
| `PipelineTimingsMs`  | `Dictionary<string, long>` | Tempo de cada camada (debug)        |

## 5. Atencao — Camera exclusiva

O Provvi SDK captura um frame em background e fecha a câmera imediatamente.
Se o app HabilitAi tiver preview de câmera ativo (para liveness do ML Kit),
**feche o preview antes de chamar `CaptureHabilitAiAsync()`**.
```csharp
// Correto:
await _mlKit.StopPreviewAsync();           // fecha preview do ML Kit
var result = await _capture               // SDK abre câmera, captura, fecha
    .CaptureHabilitAiAsync(request);
await _mlKit.StartPreviewAsync();          // reabre preview se necessário
```

## 6. Suporte

Dúvidas de integração: abrir issue no repositório provvi ou contato direto.
