# Building and packaging

The current PowerShell build uses libraries from existing Minecraft installations.
It does not download or redistribute Minecraft, NeoForge or Verity dependencies.
Use Java 21 and matching Minecraft 1.21.1 / NeoForge installations.

```powershell
./build.ps1 -ServerRoot 'C:/path/to/server' -ClientRoot 'C:/path/to/minecraft-install' -JavaHome 'C:/path/to/jdk21'
```

`ClientRoot` contains `libraries/`; it is not the CurseForge instance directory.
`ServerRoot` also contains its matching `libraries/`. Keep unrelated versions out
of these dependency roots. The build compiles `src/main/java`, copies only runtime
resources, includes the addon license and writes the versioned JAR into `dist/`.
It uses Java's `-Xlint` diagnostics; existing legacy deprecation/unchecked warnings
are reported rather than hidden. A successful compilation is not a compatibility
claim for arbitrary modpacks.

```powershell
./package-release.ps1 -JavaHome 'C:/path/to/jdk21'
```

Run packaging after the build. It verifies the JAR's metadata, rejects test,
diagnostic, original-Verity, secret and recording entries, then creates a separate
server-service ZIP and SHA256SUMS.txt. The same single universal addon JAR is used
on clients and servers. Python code and configuration examples belong only in the
server-service ZIP. The archive contains no configured credentials or models.

Release publication is separate from packaging. Upload the JAR, service ZIP and
checksums with release notes. Keep the beta/prerelease designation until broad
in-game validation warrants a stable release. Local regression harnesses and
historical diagnostics are deliberately not part of the public tree or artifacts.
