using System.Net;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Configuration;
using Xunit;

namespace Harbor.Api.Tests;

public sealed class HealthEndpointsTests : IClassFixture<WebApplicationFactory<Program>>
{
    private readonly WebApplicationFactory<Program> _factory;

    public HealthEndpointsTests(WebApplicationFactory<Program> factory)
    {
        _factory = factory;
    }

    [Fact]
    public async Task Liveness_returns_ok_even_when_postgres_is_unavailable()
    {
        using var factory = CreateFactoryWithConnectionString(
            "Host=127.0.0.1;Port=1;Database=harbor;Username=harbor;Password=unavailable;Timeout=1;Command Timeout=1");
        using var client = factory.CreateClient();

        var response = await client.GetAsync("/health/live");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task Readiness_returns_service_unavailable_when_postgres_is_unavailable()
    {
        using var factory = CreateFactoryWithConnectionString(
            "Host=127.0.0.1;Port=1;Database=harbor;Username=harbor;Password=unavailable;Timeout=1;Command Timeout=1");
        using var client = factory.CreateClient();

        var response = await client.GetAsync("/health/ready");

        Assert.Equal(HttpStatusCode.ServiceUnavailable, response.StatusCode);
    }

    [Fact]
    public async Task Api_root_reports_v1()
    {
        using var client = _factory.CreateClient();
        var response = await client.GetAsync("/api/v1");
        response.EnsureSuccessStatusCode();
        var body = await response.Content.ReadAsStringAsync();
        Assert.Contains("Harbor API", body);
        Assert.Contains("v1", body);
    }

    private WebApplicationFactory<Program> CreateFactoryWithConnectionString(string connectionString)
    {
        return _factory.WithWebHostBuilder(builder =>
        {
            builder.ConfigureAppConfiguration((_, configuration) =>
            {
                configuration.AddInMemoryCollection(new Dictionary<string, string?>
                {
                    ["ConnectionStrings:Harbor"] = connectionString
                });
            });
        });
    }
}
