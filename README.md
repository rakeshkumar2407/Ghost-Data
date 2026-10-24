# Northstar Deletion Audit

A full-stack workspace for reviewing the supplied Northstar Shop deletion requests and evidence, documenting findings and remediation plans, and exporting the required `output.csv`.

The `dataset/` folder contains the challenge data from the supplied [HackerRank Orchestrate repository](https://github.com/interviewstreet/hackerrank-orchestrate-october26/tree/main/code), pinned to commit `acb3dfca6e94e4803a26e72c49074a488de93c93`: 518 requests, supporting CSVs, the evidence manifest, and all 94 evidence files. The application reads this bundled copy; it makes no GitHub, CDN, or third-party data requests at runtime.

The app loads the included dataset into PostgreSQL at first startup and then keeps saved audit decisions in the database. Its admin console includes a customer directory with identity history and linked privacy requests, searchable views of imported source tables, an operational activity feed, and the deletion-audit workflow. Reference screenshots included with the dataset are not shown as application features because they are not operational records; there is no separate evidence-library page or customer-facing portal. Current and historical identifiers are resolved from the identity files. Explicit statements that the person is not requesting deletion are classified as `NOT_APPLICABLE` with no steps; ambiguous intent is shown for review. For other cases, optional OpenAI or Google Gemini analysis proposes a status and ordered steps. Individual recommendations require operator review and save; the confirmed batch action saves each validated provider result sequentially. The analysis does not execute destructive operations. The provider receives the request, matched identity/source rows, deletion rules, evidence manifest, and matching excerpts from text evidence. PDF and image bytes are not sent or inspected by the analysis.

## Stack

- Frontend: React, Vite, TypeScript, CSS
- Backend: Java 21, Spring Boot 3.3, Spring Data JPA, Flyway
- Database: PostgreSQL 16
- Local services: Docker Compose

## Run locally

Prerequisites: Docker Desktop for the all-in-one run, or Java 21, Maven 3.9+, Node.js 20+, and npm for local development.

### Run the full stack with Docker

```powershell
docker compose up --build
```

Open `http://localhost:3000`. The API is also available at `http://localhost:8080`.

### Run services individually

1. Start PostgreSQL:

   ```powershell
   docker compose up -d postgres
   ```

2. Start the API in a PowerShell window:

   ```powershell
   Set-Location backend
   mvn spring-boot:run
   ```

   Flyway creates the schema on startup.

3. Start the UI in another PowerShell window:

   ```powershell
   Set-Location frontend
   npm install
   npm run dev
   ```

   Open `http://localhost:5173`. Vite proxies API requests to port 8080 by default; set `VITE_API_PROXY_TARGET` to proxy to a different local API port.

For Google Gemini analysis, create an API key in [Google AI Studio](https://aistudio.google.com/apikey), then set `AI_PROVIDER=gemini` and `GEMINI_API_KEY` in the backend environment (or the environment used by Docker Compose). The default model is `gemini-3.8-flash`, using Google's OpenAI-compatible Gemini endpoint. Google currently lists a free tier for this model; free-tier quotas and availability can change. On the free tier, Google may use submitted prompts and responses to improve its products, so do not send real personal data without an approved privacy/security review. This app sends request and evidence data, including personal identifiers, to the configured provider.

For local development, set the provider and key before starting the backend:

```powershell
$env:AI_PROVIDER = "gemini"
$secureKey = Read-Host "Gemini API key" -AsSecureString
$keyPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
try {
    $env:GEMINI_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPointer)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer)
}
Set-Location backend
mvn spring-boot:run
```

For OpenAI, configure `OPENAI_API_KEY`; the default model is `gpt-4.1-mini`. `OPENAI_MODEL` and `OPENAI_BASE_URL` remain supported. `AI_MODEL` and `AI_BASE_URL` can override the model and endpoint for either provider. Without the selected provider's key, the rest of the application remains usable and analysis reports an explicit configuration error.

For a non-local database, set `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD`. Set `POSTGRES_PASSWORD` to override the local Compose password.

## Workflow

1. On first startup, the bundled `deletion_requests.csv` and supporting source CSVs are imported automatically. For another dataset, use the import action.
2. Use **Customers** to search imported customer profiles and inspect identity history, related deletion requests, and directly linked activity. Use **Data sources** to search and page through all six data tables (including deletion requests), or **System activity** to inspect recorded operations.
3. Review request details and the resolved current/historical identity trail, then inspect matching source CSV records and evidence identifiers. No order, ticket, or other operational data is fabricated when it is not present in the supplied tables.
4. Run AI analysis for an individual case and review its recommendation, or use **Analyze pending with AI** to process pending cases sequentially. The batch action requires confirmation, sends case data to the configured AI provider, saves each validated result, and can be cancelled. Provider usage may be billed or subject to free-tier quotas; a failed case stops the batch while keeping earlier saves.
5. Record or review findings, remediation actions, and verification checks with system, resource, operation, state, and evidence identifiers. The API sorts saved steps into the challenge's required phase/operation/resource order.
6. Save the appropriate status: `COMPLETE`, `INCOMPLETE`, or `NOT_APPLICABLE`.
7. Export `output.csv`. Export is blocked until every imported request has a reviewed status.

## Challenge submission

`evaluation/evaluation_report.md` records the current evaluation status and its known limitations. The sample-output contract test runs as part of `mvn test`. To package the source and bundled dataset as `code.zip`, run:

```powershell
.\scripts\package-submission.ps1
```

The AI accuracy metrics remain unmeasured until the 32 sample cases are actually analyzed with a configured provider key.

`NOT_APPLICABLE` is accepted only when the request text clearly says deletion was not requested and must have no steps. `COMPLETE` may contain verification steps only. `INCOMPLETE` must contain findings, remediation, and verification phases in that order. The API checks the allowed operation/phase/state combinations, sorts steps by the required phase/operation/system/resource order, and assigns consecutive step numbers. An operator remains responsible for confirming the evidence is sufficient to prove the policy requirements, including sync/restore safety and retention.

Evidence files are served only from the bundled local `dataset/evidence/` folder using the supplied manifest. Supporting CSV rows are stored in PostgreSQL; evidence excerpts used for AI are read from the configured local dataset directory. The analysis currently sends matched text excerpts and the manifest, not image/PDF contents. Do not enable an external provider for real personal data without an approved privacy/security review. Production deployment should add authentication, authorization, encryption/key management, retention controls, and malware scanning.

## API

- `GET /api/dashboard` — counts and source-file totals
- `GET /api/admin/overview` — database and audit summary for the admin console
- `GET /api/admin/tables` — imported table names and row counts
- `GET /api/admin/tables/{sourceFile}?search=&page=&size=` — paged, searchable imported rows
- `GET /api/admin/customers/{userId}` — customer profile, identity history, linked deletion requests, and directly linked system activity
- `GET /api/cases?search=&status=&page=&size=` — paged case list; `status` accepts `PENDING`, `COMPLETE`, `INCOMPLETE`, or `NOT_APPLICABLE` (omit or use `ALL` for every request)
- `GET /api/cases/{caseId}` — request details and saved steps
- `POST /api/cases/{caseId}/analyze` — prepare a provider-backed audit recommendation for operator review
- `GET /api/cases/{caseId}/identities` — resolve current and historical identifiers
- `GET /api/source-records?search=` — search imported CSV evidence rows
- `GET /api/evidence` — list local manifest-backed evidence files
- `GET /api/evidence/{fileId}/content` — read local evidence content by manifest file ID
- `PUT /api/cases/{caseId}/audit` — save a reviewed outcome and ordered steps
- `POST /api/import` — multipart CSV import (expects `deletion_requests.csv`)
- `GET /api/export` — download the output CSV; rejects pending cases
- `GET /api/health` — service health
