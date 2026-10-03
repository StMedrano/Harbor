using Microsoft.EntityFrameworkCore;

namespace Harbor.Api.Data;

public sealed class HarborDbContext(DbContextOptions<HarborDbContext> options) : DbContext(options);
