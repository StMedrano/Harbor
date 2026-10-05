import { assertEquals } from "jsr:@std/assert@1";
import { protectFile } from "../hosted/notification-acceptance.ts";
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
