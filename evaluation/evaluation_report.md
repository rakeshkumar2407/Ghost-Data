# Evaluation report

## Scope and current result

The supplied `sample_output.csv` contains 32 gold-standard cases. The repository test `SampleOutputContractTest` read all 32 rows and passed checks for statuses, JSON structure, operation/state pairs, citations, consecutive step numbers, and canonical sequence order.

This is a contract check against the reference file, not a comparison against AI-generated predictions. No OpenAI key was configured in this environment, so no provider analyses were run and accuracy cannot be reported honestly.

| Metric | Result |
| --- | --- |
| Sample reference rows checked | 32 / 32 |
| Case-level status accuracy | Not measured — no AI predictions |
| Step-level accuracy | Not measured — no AI predictions |
| Field-level accuracy | Not measured — no AI predictions |
| Sequence accuracy | Not measured — no AI predictions |
| Provider requests made | 0 |
| Bundled reference screenshots | Not presented as application features |

## Admin console data validation

The admin workspace is backed by the imported challenge records rather than mock business data. The API integration test verifies 850 customer profiles, 4,011 system activity rows, and six browsable data tables (including deletion requests and the evidence manifest). It also checks paged customer search and retrieval of a customer profile with linked identity history, deletion requests, and activity. Supplied UI screenshots are not shown as project features because they are reference images rather than operational records. No customer-facing portal, fabricated orders, or fabricated support tickets were added. The final backend suite passes all 21 tests, and the React production build passes.

## Additional team-created evaluation cases

These acceptance cases extend the supplied sample fixture. They are covered by the backend's intent, audit validation, evidence-reference, and dataset integration tests; they have not been scored against the AI provider.

| Case | Scenario | Expected result |
| --- | --- | --- |
| TEAM-001 | The person asks to stop promotional email and explicitly says they are not asking for deletion. | `NOT_APPLICABLE`, with `[]`, without an OpenAI request. |
| TEAM-002 | A completion verification cites an evidence ID absent from the supplied evidence manifest and records. | Reject the audit; do not accept an invented citation. |
| TEAM-003 | An incomplete audit includes a finding, proposed deletion, and future verification. | Finding cites supplied evidence; remediation and future verification have empty `evidence_id`; output is canonically ordered. |

## Failures found and improvements made

- Earlier dataset tests expected one non-deletion request; inspection found three explicit non-deletion requests (`case_534`, `case_536`, and `case_542`). The expected dashboard counts and integration coverage were corrected.
- Mockito's shared case stub caused strict-test failures. The stubbing was made lenient where shared setup is appropriate.
- Audit saves previously accepted evidence identifiers without checking the supplied files. Citation validation now checks manifest `file_id` values and exact `activity_id` / `audit_id` values from imported source rows.
- Step validation now enforces evidence on findings and completed verifications, and empty evidence IDs on proposed remediation and future verification steps.
- An optional OpenAI analysis endpoint and review UI were added. Analysis is conservative and exposes caveats; the operator must review and save the proposed result.

## Known limitations and next evaluation step

- Configure `OPENAI_API_KEY` and run analyses on the 32 sample requests before reporting model accuracy or claiming sample-answer parity.
- The agent sends matched source records and matching text evidence excerpts to the configured provider. Image and PDF contents are not extracted or sent, so cases depending on visual/document contents may remain incomplete.
- The required full benchmark, including field-by-field AI comparison and sequence scoring, remains unrun. Review the resulting failures and revise the agent before processing all 518 requests.
- The dashboard now provides an explicit, billable batch action to analyze and save pending cases sequentially. It stops on the first failed case, preserves prior saves, and supports cancellation; successful batch execution and export still require a configured API key.
- Dataset screenshots are reference artifacts rather than operational audit records, so they are not surfaced in the admin UI. The evidence manifest remains available as imported source data.
