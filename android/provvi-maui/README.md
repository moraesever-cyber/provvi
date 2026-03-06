# Provvi SDK — Binding MAUI Android

Wrapper .NET/MAUI para o SDK Provvi Android, permitindo captura autenticada C2PA
em aplicativos HabilitAi e outros integradores que usam .NET MAUI.

## Estrutura

```
android/provvi-maui/
├── provvi-maui.csproj              # Projeto .NET 8 Android Library
├── libs/
│   └── provvi-sdk-release.aar      # SDK Provvi Android (copiado de build/outputs/aar/)
└── src/
    ├── ProvviCapture.cs            # Wrapper principal — bridge JNI para SDK Kotlin
    └── ProvviMauiExtensions.cs     # DI extensions para MauiProgram.cs
```

## Requisitos

- .NET 8 SDK com workload `android` instalado
- Android API 26+ (minSdk do SDK Provvi)
- Permissões no `AndroidManifest.xml` do app integrador:
  - `android.permission.CAMERA`
  - `android.permission.ACCESS_FINE_LOCATION`
  - `android.permission.INTERNET`

## Instalação

### 1. Adicionar referência de projeto

No `.csproj` do app MAUI integrador:

```xml
<ProjectReference Include="../../../android/provvi-maui/provvi-maui.csproj" />
```

Ou adicionar a DLL compilada diretamente:

```xml
<Reference Include="Provvi.Maui">
  <HintPath>path/to/Provvi.Maui.dll</HintPath>
</Reference>
```

### 2. Registrar no container DI

Em `MauiProgram.cs`:

```csharp
using Provvi.Maui;

public static class MauiProgram
{
    public static MauiApp CreateMauiApp()
    {
        var builder = MauiApp.CreateBuilder();
        builder
            .UseMauiApp<App>()
            .Services.AddProvvi(options =>
            {
                options.LambdaUrl = "https://xyz.lambda-url.sa-east-1.on.aws/";
                options.ApiKey    = "sua-api-key-aqui"; // DT-005
            });

        return builder.Build();
    }
}
```

### 3. Solicitar permissões

Antes de chamar `CaptureAsync`, solicite as permissões em tempo de execução:

```csharp
var cameraStatus   = await Permissions.RequestAsync<Permissions.Camera>();
var locationStatus = await Permissions.RequestAsync<Permissions.LocationWhenInUse>();

if (cameraStatus != PermissionStatus.Granted || locationStatus != PermissionStatus.Granted)
{
    // exibir mensagem ao usuário
    return;
}
```

## Uso

```csharp
public class VistoriaViewModel
{
    private readonly ProvviCapture _capture;

    public VistoriaViewModel(ProvviCapture capture)
    {
        _capture = capture;
    }

    public async Task CapturarFotoAsync(string placaVeiculo)
    {
        try
        {
            var result = await _capture.CaptureAsync(
                referenceId: placaVeiculo,
                capturedBy:  "HabilitAi Vistoria"
            );

            Console.WriteLine($"Sessão: {result.SessionId}");
            Console.WriteLine($"Hash:   {result.FrameHashHex}");
            Console.WriteLine($"URL:    {result.ManifestUrl}");
        }
        catch (ProvviCaptureException ex) when (ex.ErrorCode == ProvviErrorCode.DeviceCompromised)
        {
            // Dispositivo com root/Magisk — rejeitar captura
        }
        catch (ProvviCaptureException ex) when (ex.ErrorCode == ProvviErrorCode.RecaptureSuspected)
        {
            // Possível fraude — foto de foto detectada
        }
        catch (ProvviCaptureException ex)
        {
            Console.WriteLine($"Erro ({ex.ErrorCode}): {ex.Message}");
        }
    }
}
```

## Uso HabilitAi

```csharp
var result = await _capture.CaptureHabilitAiAsync(new HabilitAiCaptureRequest
{
    ClassId  = aulaId,
    DeviceId = Android.Provider.Settings.Secure.GetString(
                   ContentResolver,
                   Android.Provider.Settings.Secure.AndroidId) ?? "unknown",
    Event    = HabilitAiEvent.StudentStartBiometry
});
```

## Códigos de erro

| `ProvviErrorCode`       | Causa                                                        |
|-------------------------|--------------------------------------------------------------|
| `PermissionDenied`      | Câmera ou GPS sem permissão concedida                        |
| `DeviceCompromised`     | Root, Magisk ou Play Integrity falhou                        |
| `MockLocationDetected`  | GPS simulado via apps de localização fake                    |
| `RecaptureSuspected`    | Moiré ou reflexo especular detectado (foto de tela/foto)     |
| `SigningFailed`         | Falha ao assinar manifesto C2PA localmente                   |
| `CaptureError`          | Falha de câmera ou timeout                                   |
| `Unknown`               | Erro inesperado — ver mensagem da exceção                    |

## Arquitetura da bridge

O wrapper usa JNI direto (`Android.Runtime.JNIEnv`) em vez de bindings automáticos
porque o SDK Provvi é entregue como `.aar` pré-compilado sem código-fonte Java/Kotlin.
Bindings automáticos do MAUI requerem acesso às APIs públicas em formato `.jar` de stubs.

A bridge resolve os nomes de método em runtime — atualizações do SDK que renomearem
métodos públicos requerem atualização correspondente em `ProvviCapture.cs`.

## Débitos técnicos pendentes

- **DT-005**: API Key hardcoded no integrador — aguarda DT-008 (licenciamento por cliente)
- **Coroutine bridge**: `captureBlocking()` precisa ser adicionado ao SDK Kotlin como
  helper para chamadores não-coroutine (Java/C#). Sem esse método, a bridge JNI não
  consegue invocar suspend functions diretamente.
- **iOS**: binding `.xcframework` para MAUI não implementado — aguarda Fase 1 iOS

## Compilar

```bash
cd android/provvi-maui
dotnet build -c Release
```

O artefato gerado é `bin/Release/net8.0-android/Provvi.Maui.dll`.
