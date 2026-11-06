# Published repository secret audit

Audited the six commits through `19cd19a`, including deleted files and the exact `origin/main` tree. Gitleaks 8.30.1, downloaded from its official release and verified against its SHA-256 checksum, found no exposed credentials. Additional rules checked literal password assignments and passwords documented inline. No private-key files, credential files or server.properties files appear in the published history.

The available Linux CI log was checked too. Its one additional-rule hit was `setup-java` printing the environment variable name `GITHUB_TOKEN` for Maven configuration; it was not a token value. The configuration allows only that exact variable name for this rule. Workflows reference GitHub's temporary token rather than embedding a credential. Release artifacts contain build outputs and reports, not local server configuration or SSH files.

`.gitleaks.toml` retains the scanner's default rules and adds the password checks. The Secret scan workflow scans complete history on pushes and pull requests, verifies the scanner download checksum, and redacts findings. Runtime folders, local environment files, server configuration and downloaded tools remain ignored by Git.

No credential was found that requires history removal or rotation. This is a detection result, not a guarantee against every possible secret format. Scanner evidence stays in the ignored `build/security-audit` directory; secret values are never printed or committed in an audit report.
