using Android.Content;
using Microsoft.Extensions.DependencyInjection;

namespace Provvi.Maui;

// ---------------------------------------------------------------------------
// Configuração do SDK para injeção de dependência MAUI
// ---------------------------------------------------------------------------

/// <summary>
/// Opções de configuração do SDK Provvi para integração MAUI.
/// Configure via MauiAppBuilder.Services em MauiProgram.cs.
/// </summary>
public sealed class ProvviOptions
{
    /// <summary>
    /// URL da Lambda de assinatura Provvi.
    /// Exemplo: "https://xyz.lambda-url.sa-east-1.on.aws/"
    /// </summary>
    public string LambdaUrl { get; set; } = "";

    /// <summary>
    /// API Key de autenticação. Deixar vazio para ambientes de desenvolvimento
    /// sem autenticação configurada.
    /// </summary>
    public string ApiKey { get; set; } = "";
}

/// <summary>
/// Extensions de registro do SDK Provvi no container DI MAUI.
///
/// Uso em MauiProgram.cs:
/// <code>
///   builder.Services.AddProvvi(options => {
///       options.LambdaUrl = "https://xyz.lambda-url.sa-east-1.on.aws/";
///       options.ApiKey    = Environment.GetEnvironmentVariable("PROVVI_API_KEY") ?? "";
///   });
/// </code>
///
/// Consumo na ViewModel:
/// <code>
///   public MyViewModel(ProvviCapture capture) { _capture = capture; }
/// </code>
/// </summary>
public static class ProvviMauiExtensions
{
    /// <summary>
    /// Registra <see cref="ProvviCapture"/> como serviço singleton no container DI MAUI.
    /// </summary>
    public static IServiceCollection AddProvvi(
        this IServiceCollection services,
        Action<ProvviOptions> configure)
    {
        var options = new ProvviOptions();
        configure(options);

        if (string.IsNullOrWhiteSpace(options.LambdaUrl))
            throw new InvalidOperationException(
                "ProvviOptions.LambdaUrl é obrigatório. Configure via AddProvvi().");

        services.AddSingleton(sp =>
        {
            // Obtém o Context Android via MAUI Application
            var context = Android.App.Application.Context
                ?? throw new InvalidOperationException(
                    "Android.App.Application.Context não disponível.");

            return new ProvviCapture(context, options.LambdaUrl, options.ApiKey);
        });

        return services;
    }
}
