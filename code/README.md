# Packaged application source

This directory contains the source included in `code.zip`. Keep the challenge-provided `dataset/` directory beside this `code/` directory; it is intentionally not copied into the submission archive.

## Run the backend

Prerequisites: Java 21, Maven 3.9+, and a running PostgreSQL instance. From the challenge repository root:

```powershell
Set-Location code\backend
$env:DATASET_DIRECTORY = (Resolve-Path ..\..\dataset).Path
mvn test
mvn spring-boot:run
```

The backend listens on `http://localhost:8080`. Database connection settings can be supplied with `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD`.

## Run the frontend

In another PowerShell window, from the challenge repository root:

```powershell
Set-Location code\frontend
npm ci
npm run dev
```

Open `http://localhost:5173`. Vite proxies API calls to the backend at port 8080 by default.

## Analyze and export

Set `AI_PROVIDER` and the matching provider key (`OPENAI_API_KEY` or `GEMINI_API_KEY`) in the backend environment before using AI analysis. Never put keys in the ZIP, source, or chat transcript. The application sends case and matching evidence data to that provider; use only credentials and data you are authorized to process. Provider use may incur charges.

Use the admin workspace to review cases, run the confirmed sequential analysis for pending requests, and export `output.csv` only after every request has a reviewed status. The CSV is a separate challenge submission and is not included in `code.zip`. Review every recommendation; AI output is not an executed deletion.

Before submission, validate the CSV against the challenge input and evidence references from the repository root:

```powershell
.\code\scripts\validate-output.ps1
```

To validate the supplied 32-case reference contract instead:

```powershell
.\code\scripts\validate-output.ps1 -OutputPath .\dataset\sample_output.csv -RequestsFile sample_deletion_requests.csv
```
