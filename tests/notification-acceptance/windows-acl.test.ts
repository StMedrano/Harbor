import { assertEquals } from "jsr:@std/assert@1";
import {
  assertRestrictedAcl,
  protectDirectory,
  protectFile,
} from "../hosted/notification-acceptance.ts";
Deno.test({
  name:
    "Unix directory protection applies mode 0700 to the requested directory",
  ignore: Deno.build.os === "windows" ||
    (await Deno.permissions.query({ name: "write" })).state !== "granted",
  fn: async () => {
    const repository = await Deno.realPath(Deno.cwd());
    const directory = await Deno.makeTempDir({
      dir: repository,
      prefix: ".acl-test-",
    });
    try {
      await Deno.chmod(directory, 0o755);
      await protectDirectory(new URL("file://" + directory + "/"));
      assertEquals((await Deno.stat(directory)).mode! & 0o777, 0o700);
    } finally {
      const resolved = await Deno.realPath(directory);
      if (!resolved.startsWith(repository + "/.acl-test-")) {
        throw Error("Unsafe test cleanup path");
      }
      await Deno.remove(resolved, { recursive: true });
    }
  },
});
Deno.test({
  name:
    "Windows retained operator recovery directory can be protected without elevated audit privileges",
  ignore: Deno.build.os !== "windows" ||
    !Deno.args.includes("--existing-recovery"),
  fn: async () => {
    await protectDirectory();
  },
});
Deno.test({
  name:
    "Windows retained journal can be protected repeatedly without changing contents",
  ignore: Deno.build.os !== "windows" ||
    !Deno.args.includes("--existing-recovery"),
  fn: async () => {
    const file = new URL(
      "../../.superpowers/notification-operator/journal.json",
      import.meta.url,
    );
    const before = await Deno.readFile(file);
    await protectFile(file);
    await protectFile(file);
    const after = await Deno.readFile(file);
    assertEquals(
      before.length === after.length &&
        before.every((byte, index) => byte === after[index]),
      true,
    );
  },
});
Deno.test({
  name:
    "Windows directory protection removes extra readers without an audit privilege",
  ignore: Deno.build.os !== "windows" ||
    (await Deno.permissions.query({ name: "run", command: "powershell.exe" }))
        .state !== "granted",
  fn: async () => {
    const repository = await Deno.realPath(Deno.cwd());
    const directory = await Deno.makeTempDir({
      dir: repository,
      prefix: ".acl-test-",
    });
    const script = await Deno.makeTempFile({ suffix: ".ps1" });
    try {
      await Deno.writeTextFile(
        script,
        "$ErrorActionPreference='Stop'; $item=[IO.DirectoryInfo]::new($args[0]); $acl=$item.GetAccessControl(); $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new('S-1-1-0'),'Read','Allow')); $item.SetAccessControl($acl)",
      );
      const grant = await new Deno.Command("powershell.exe", {
        args: ["-NoProfile", "-NonInteractive", "-File", script, directory],
        stdout: "null",
        stderr: "piped",
      }).output();
      if (!grant.success) throw Error(new TextDecoder().decode(grant.stderr));
      await protectFile(
        new URL(
          "file:///" + (directory + "/retained.json").replaceAll("\\", "/"),
        ),
      );
      await protectDirectory(
        new URL("file:///" + directory.replaceAll("\\", "/") + "/"),
      );
      await Deno.writeTextFile(
        script,
        "$env:PSModulePath=Join-Path $PSHOME 'Modules'; $ErrorActionPreference='Stop'; $acl=Get-Acl -LiteralPath $args[0]; @{owner=$acl.GetOwner([Security.Principal.SecurityIdentifier]).Value;current=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value;protected=$acl.AreAccessRulesProtected;readers=@($acl.Access|ForEach-Object {$_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value})}|ConvertTo-Json -Compress",
      );
      const check = await new Deno.Command("powershell.exe", {
        args: ["-NoProfile", "-NonInteractive", "-File", script, directory],
        stdout: "piped",
        stderr: "piped",
      }).output();
      if (!check.success) throw Error(new TextDecoder().decode(check.stderr));
      assertRestrictedAcl(JSON.parse(new TextDecoder().decode(check.stdout)));
      await Deno.writeTextFile(
        directory + "/probe.txt",
        "synthetic; no credentials",
      );
      assertEquals(
        await Deno.readTextFile(directory + "/probe.txt"),
        "synthetic; no credentials",
      );
    } finally {
      const resolved = await Deno.realPath(directory);
      if (!resolved.startsWith(repository + "\\.acl-test-")) {
        throw Error("Unsafe test cleanup path");
      }
      await Deno.remove(resolved, { recursive: true });
      await Deno.remove(script);
    }
  },
});
Deno.test({
  name:
    "Windows protection removes a pre-existing explicit extra-reader file ACE",
  ignore: Deno.build.os !== "windows" ||
    (await Deno.permissions.query({ name: "run", command: "powershell.exe" }))
        .state !== "granted",
  fn: async () => {
    const file = await Deno.makeTempFile(),
      script = await Deno.makeTempFile({ suffix: ".ps1" });
    try {
      await Deno.writeTextFile(file, "synthetic fixture; no credentials");
      await Deno.writeTextFile(
        script,
        "$env:PSModulePath=Join-Path $PSHOME 'Modules'; $ErrorActionPreference='Stop'; $acl=Get-Acl -LiteralPath $args[0]; $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new('S-1-1-0'),'Read','Allow')); Set-Acl -LiteralPath $args[0] -AclObject $acl",
      );
      const grant = await new Deno.Command("powershell.exe", {
        args: ["-NoProfile", "-NonInteractive", "-File", script, file],
        stdout: "null",
        stderr: "piped",
      }).output();
      if (!grant.success) throw Error(new TextDecoder().decode(grant.stderr));
      const url = new URL("file:///" + file.replaceAll("\\", "/"));
      await protectFile(url); // Reads actual resulting ACL and refuses any non-operator/SYSTEM reader.
      assertEquals(
        await Deno.readTextFile(file),
        "synthetic fixture; no credentials",
      );
    } finally {
      await Deno.remove(file);
      await Deno.remove(script);
    }
  },
});
