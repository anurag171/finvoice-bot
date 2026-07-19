# Architecture

## Class diagram — Controller → Strategy → Skills

```mermaid
classDiagram
    class ChatBotController {
        -AgentSkillStrategy agentSkillStrategy
        -ChatHistoryService chatHistoryService
        +sendMessage(userId, sessionId, message, invoiceImage) ChatResponse
        +listSessions(userId) List~String~
        +getSession(sessionId) List~ChatMessage~
        +listInvoices(userId) List~InvoiceRecord~
    }

    class AgentSkillStrategy {
        -List~ChatSkill~ orderedSkills
        -HelpSkill fallbackSkill
        +route(SkillRequest) ChatResponse
    }

    class ChatSkill {
        <<interface>>
        +canHandle(SkillRequest) boolean
        +handle(SkillRequest) ChatResponse
        +name() String
        +triggerDescription() String
        +priority() int
    }

    class HelpSkill
    class ScanInvoiceSkill
    class ReadAloudSkill
    class StatusSkill

    class OcrService {
        +extractText(bytes, filename) String
    }
    class InvoiceParserService {
        +parse(rawOcrText) ParsedInvoice
    }
    class PaymentGatewayFactory {
        +resolve(userMessage) PaymentGateway
        +byType(GatewayType) PaymentGateway
    }
    class PaymentGateway {
        <<interface>>
        +type() GatewayType
        +initiatePayment(ParsedInvoice) PaymentInitiationResult
        +checkStatus(referenceId) PaymentStatusResult
    }
    class RazorpayGateway
    class StripeGateway
    class TextToSpeechService {
        +synthesizeToFile(text) String
    }
    class ChatHistoryService {
        +recordUserTurn(SkillRequest)
        +recordBotTurn(SkillRequest, ChatResponse)
        +saveInvoice(...) InvoiceRecord
        +savePayment(PaymentRecord) PaymentRecord
    }

    ChatBotController --> AgentSkillStrategy
    ChatBotController --> ChatHistoryService
    AgentSkillStrategy --> ChatSkill : routes to
    AgentSkillStrategy --> HelpSkill : fallback
    ChatSkill <|.. HelpSkill
    ChatSkill <|.. ScanInvoiceSkill
    ChatSkill <|.. ReadAloudSkill
    ChatSkill <|.. StatusSkill

    ScanInvoiceSkill --> OcrService
    ScanInvoiceSkill --> InvoiceParserService
    ScanInvoiceSkill --> PaymentGatewayFactory
    ScanInvoiceSkill --> TextToSpeechService
    ScanInvoiceSkill --> ChatHistoryService
    ReadAloudSkill --> ChatHistoryService
    ReadAloudSkill --> TextToSpeechService
    StatusSkill --> ChatHistoryService
    StatusSkill --> PaymentGatewayFactory

    PaymentGatewayFactory --> PaymentGateway
    PaymentGateway <|.. RazorpayGateway
    PaymentGateway <|.. StripeGateway
```

## Sequence diagram — "scan invoice" happy path

```mermaid
sequenceDiagram
    actor User
    participant UI as Chat UI (Thymeleaf/JS)
    participant Ctrl as ChatBotController
    participant Strat as AgentSkillStrategy
    participant Scan as ScanInvoiceSkill
    participant OCR as OcrService (Vision)
    participant Parser as InvoiceParserService
    participant PayF as PaymentGatewayFactory
    participant Gw as Razorpay/StripeGateway
    participant TTS as TextToSpeechService
    participant Hist as ChatHistoryService
    participant DB as SQLite/Postgres

    User->>UI: attach invoice.png + "scan invoice"
    UI->>Ctrl: POST /api/chat (multipart)
    Ctrl->>Hist: recordUserTurn()
    Hist->>DB: insert chat_message (user turn)
    Ctrl->>Strat: route(SkillRequest)
    Strat->>Scan: canHandle() -> true
    Strat->>Scan: handle(request)
    Scan->>OCR: extractText(imageBytes)
    OCR-->>Scan: raw OCR text
    Scan->>Parser: parse(rawText)
    Parser-->>Scan: ParsedInvoice
    Scan->>Hist: saveInvoice(parsed)
    Hist->>DB: insert invoice_record
    Scan->>PayF: resolve(userMessage)
    PayF-->>Scan: PaymentGateway (Razorpay or Stripe)
    Scan->>Gw: initiatePayment(parsed)
    Gw-->>Scan: PaymentInitiationResult
    Scan->>Hist: savePayment(record)
    Hist->>DB: insert payment_record
    Scan->>TTS: synthesizeToFile(confirmationText)
    TTS-->>Scan: audioUrl
    Scan-->>Strat: ChatResponse(message, ParsedInvoice, audioUrl)
    Strat-->>Ctrl: ChatResponse
    Ctrl->>Hist: recordBotTurn()
    Hist->>DB: insert chat_message (bot turn)
    Ctrl-->>UI: 200 OK { message, data, audioUrl }
    UI-->>User: chat bubble + invoice card + <audio> confirmation
```

## Why a Strategy pattern instead of an LLM router

- **Zero marginal token cost per message.** Routing is O(n) keyword/pattern matching across a
  small, fixed skill list — no LLM call in the hot path. The only paid calls are the ones that
  do real work: Vision OCR, Google TTS, and gateway API calls.
- **Deterministic and auditable.** For any given user message, you can compute which skill will
  run *before* running it. That matters when the flow ends in moving money.
- **Cheap to extend.** A sixth skill (e.g. a "cancel payment" or "refund" skill) is a new
  `@Component` implementing `ChatSkill` plus one row in `agent-skills.md` — no router changes.

## Data model

- `chat_message` — every user/bot turn, with the producing skill name, any structured
  `dataJson`, and an optional `audioUrl`. Powers the sidebar's "past conversations" and full
  transcript replay.
- `invoice_record` — every parsed invoice (fields + raw OCR text for audit), linked to the
  session it was scanned in.
- `payment_record` — every gateway response (initiation and subsequent status checks), linked
  to the invoice it was triggered for, including the gateway's raw JSON for audit/dispute
  resolution.

## Scaling path

Start on the default `sqlite` profile for local dev / single-user demos (zero setup, one file).
For multi-user production, activate the `postgres` Spring profile
(`--spring.profiles.active=postgres` or `SPRING_PROFILES_ACTIVE=postgres`) and point `DB_URL` /
`DB_USERNAME` / `DB_PASSWORD` at a real Postgres instance — no code changes required, only the
active profile and its three env vars.
