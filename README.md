# finvoice-bot

**Scan it. Parse it. Pay it. Hear it confirmed.**

A Spring Boot chatbot that turns a photo of an invoice into a completed payment: Google Vision
OCR extracts the text, a deterministic parser turns it into structured fields, Razorpay or
Stripe triggers the payment, and Google Text-to-Speech reads back a spoken confirmation — all
orchestrated through a pluggable **Agent Skill Strategy** and backed by a full chat/invoice
audit trail.

> Repo name ideas: `finvoice-bot` (used here) or `paytalk-ai`.

---

## Features

- **OCR Skill** — extracts text from uploaded invoice images via Google Cloud Vision
  (`DOCUMENT_TEXT_DETECTION`, tuned for dense printed text over scene text).
- **Parser Skill** — regex/heuristic extraction of invoice number, amount, currency, date,
  payee, and account reference from raw OCR text into structured JSON. Zero marginal LLM cost.
- **Payment Skill** — triggers Razorpay **or** Stripe at runtime (say "via stripe" / "via
  razorpay", or rely on the configured default).
- **TTS Skill** — synthesizes an MP3 confirmation via Google Cloud Text-to-Speech, served back
  to the browser and playable inline.
- **Help Skill** — lists available commands; also the router's fallback.
- **Status Skill** — re-fetches *live* payment status from the gateway (not a cached snapshot).
- **Conversation history** — every chat turn, parsed invoice, and gateway response is persisted
  (SQLite by default, Postgres via a Spring profile) for a full audit trail.
- **UI** — a single-page Thymeleaf + vanilla JS chat interface with a sidebar of past
  conversations and recently scanned invoices, and inline invoice/status cards.

See [`agent-skills.md`](./agent-skills.md) for the full skill manifest and routing table, and
[`docs/architecture.md`](./docs/architecture.md) for class + sequence diagrams.

---

## Quick start

### Prerequisites

- Java 17+
- Maven 3.9+
- A Google Cloud project with the **Vision API** and **Text-to-Speech API** enabled
- A Razorpay account (test mode is fine) and/or a Stripe account (test mode is fine)

### 1. Clone and configure credentials

**Google Cloud (Vision + TTS).** The client libraries resolve credentials in this order — no
code path in this project references a key file directly, so any of these work with zero
changes:

1. `GOOGLE_APPLICATION_CREDENTIALS=/path/to/key.json` — a service-account key, if your org
   policy allows generating one.
2. **Application Default Credentials** — run `gcloud auth application-default login` locally;
   no key.json needed.
3. **Workload Identity Federation** — if running on GKE/GCP with no static key permitted at
   all, attach the appropriate IAM binding and skip both of the above entirely.

```bash
export GOOGLE_APPLICATION_CREDENTIALS=/absolute/path/to/key.json
# or: gcloud auth application-default login
```

**Payment gateways.** Never hardcode these — in any shared or production environment, source
them from Vault / a cloud Secret Manager and inject as env vars at deploy time:

```bash
export RAZORPAY_KEY_ID=rzp_test_xxxxxxxx
export RAZORPAY_KEY_SECRET=xxxxxxxxxxxxxxxx
export STRIPE_SECRET_KEY=sk_test_xxxxxxxxxxxxxxxx
```

### 2. Run

```bash
mvn spring-boot:run
```

Open `http://localhost:8080`. On first run, SQLite creates `finvoice-bot.db` in the working
directory and audio clips are written to `./audio-clips`.

### 3. Try it

- Type `help` to see available commands.
- Attach an invoice image and type `scan invoice` (or `scan invoice via stripe`).
- Type `status` to check the last payment, or `status <gateway_reference_id>` for a specific one.
- Type `read aloud` to replay the last invoice's spoken confirmation.

---

## Configuration reference

All application-specific config lives under the `finvoice.*` prefix in
`src/main/resources/application.yml`:

| Property | Default | Purpose |
|---|---|---|
| `finvoice.ocr.language-hints` | `en` | Vision OCR language hints |
| `finvoice.audio.storage-dir` | `./audio-clips` | Where synthesized MP3s are written / served from |
| `finvoice.audio.voice-name` | `en-IN-Neural2-A` | Google TTS voice |
| `finvoice.payment.default-gateway` | `RAZORPAY` | Used unless the message names a gateway explicitly |

## Switching to Postgres for multi-user

```bash
export DB_URL=jdbc:postgresql://your-host:5432/finvoicebot
export DB_USERNAME=finvoicebot
export DB_PASSWORD=********
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

No code changes needed — `hibernate.ddl-auto: update` will create the schema on first run in
either profile. For a real production rollout, replace `ddl-auto: update` with a migration tool
(Flyway/Liquibase) once the schema stabilizes.

---

## Running tests

```bash
mvn test
```

Covers: skill routing order and fallback behavior (`AgentSkillStrategyTest`), the
image-**and**-trigger-phrase precondition for `ScanInvoiceSkill`, and trigger matching for
`HelpSkill` / `StatusSkill`.

---

## Cost model

| Component | Billed by |
|---|---|
| LLM tokens | **Not used for routing** — only if you add an AI-based intent skill later |
| Google Vision API | Per image processed |
| Google Text-to-Speech | Per million characters synthesized |
| Razorpay / Stripe | Standard per-transaction gateway fees, not tokens |

---

## Security notes

- API keys/secrets are read from environment variables only — wire your deployment's Vault /
  AWS Secrets Manager / GCP Secret Manager to inject them, never commit them.
- Every OCR result, parsed JSON, gateway request/response, and generated audio clip is persisted
  for audit (`chat_message`, `invoice_record`, `payment_record`).
- `key.json`, `*.db`, and `audio-clips/` are excluded via `.gitignore` — double-check before
  your first commit if you generate a service-account key locally.

## Extending

- **Multi-user auth**: add Spring Security with your identity provider of choice; scope all
  `ChatHistoryService` queries (already keyed by `userId`) behind the authenticated principal
  instead of the current free-text `userId` field.
- **New skills**: implement `ChatSkill`, annotate `@Component`, add a row to `agent-skills.md`.
  No router changes required — see [`docs/architecture.md`](./docs/architecture.md).
- **New payment gateways**: implement `PaymentGateway`, annotate `@Component`; it's
  auto-registered into `PaymentGatewayFactory`.
