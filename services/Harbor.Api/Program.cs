using Harbor.Api.Data;
using Microsoft.AspNetCore.Diagnostics.HealthChecks;
using Microsoft.EntityFrameworkCore;

var builder = WebApplication.CreateBuilder(args);

var harborConnectionString = builder.Configuration.GetConnectionString("Harbor")
    ?? "Host=localhost;Port=5432;Database=harbor;Username=harbor;Password=harbor-local-only";

builder.Services.AddOpenApi();
builder.Services.AddDbContext<HarborDbContext>(options => options.UseNpgsql(harborConnectionString));
builder.Services
    .AddHealthChecks()
    .AddDbContextCheck<HarborDbContext>("postgres", tags: ["ready"]);

var app = builder.Build();

app.MapOpenApi();
app.MapHealthChecks("/health/live", new HealthCheckOptions
{
    Predicate = _ => false
});
app.MapHealthChecks("/health/ready", new HealthCheckOptions
{
    Predicate = registration => registration.Tags.Contains("ready")
});

app.MapGet("/api/v1", () => Results.Ok(new
{
    name = "Harbor API",
    version = "v1"
}));

app.Run();

public partial class Program;
