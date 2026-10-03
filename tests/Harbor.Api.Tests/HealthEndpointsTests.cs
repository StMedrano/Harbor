using System.Net;
using Microsoft.AspNetCore.Mvc.Testing;

namespace Harbor.Api.Tests;

public sealed class HealthEndpointsTests : IClassFixture<WebApplicationFactory<Program>>
{
    private readonly HttpClient _client;

    public HealthEndpointsTests(WebApplicationFactory<Program> factory)
    {
        _client = factory.CreateClient();
    }

    [Theory]
    [InlineData("/health/live")]
    [InlineData("/health/ready")]
    public async Task Health_endpoint_returns_ok(string path)
    {
        var response = await _client.GetAsync(path);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task Api_root_reports_v1()
    {
        var response = await _client.GetAsync("/api/v1");
        response.EnsureSuccessStatusCode();
        var body = await response.Content.ReadAsStringAsync();
        Assert.Contains("Harbor API", body);
        Assert.Contains("v1", body);
    }
}
