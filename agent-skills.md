# agent-skills.md — Agent Skill Strategy Manifest

This file is the single source of truth for what skills exist, what triggers each one, and in
what order the router (`AgentSkillStrategy`) evaluates them. Keep it in sync with the actual
`@Component` classes under `com.finvoicebot.skill.impl` — if you add, remove, or re-prioritize a
skill, update this file in the same change.

## Registered skills (routing order — lowest `priority()` number wins first)

| Priority | Skill               | Trigger                                              | What it does |
|---------:|---------------------|-------------------------------------------------------|--------------|
| 10       | `ScanInvoiceSkill`   | `"scan invoice"` **and** an attached invoice image     | Orchestrates OCR → Parser → Payment → TTS end to end. |
| 15       | `StatusSkill`        | `"status"`, optionally + a gateway reference id        | Re-fetches live payment status from Razorpay/Stripe. |
| 20       | `ReadAloudSkill`      | `"read aloud"`, `"speak"`, `"play confirmation"`       | Re-synthesizes/replays a spoken confirmation for the last scanned invoice. |
| 1000     | `HelpSkill`           | `"help"`, `"commands"`, `"menu"`, `"what can you do"`  | Lists available commands. Also the router's fallback when nothing else matches. |

## Routing notes

- **Strategy pattern, not an LLM router.** `AgentSkillStrategy` iterates the registered
  `ChatSkill` beans in ascending `priority()` order and executes the *first* whose
  `canHandle(SkillRequest)` returns `true`. This keeps routing deterministic, cheap (no LLM
  tokens spent on intent detection unless you explicitly add an AI-based skill later), and
  auditable — for any given message you can predict which skill will run before it runs.
- **Specificity beats generality.** `ScanInvoiceSkill` requires *both* the trigger phrase *and*
  an attached image — a bare "scan invoice" with no attachment intentionally does not match, so
  it falls through to `HelpSkill`, which tells the user an image is required. This is why
  `ScanInvoiceSkill` sits at priority 10 (evaluated early) but is still narrow in what it claims.
- **Never crash the router.** If a skill's `canHandle()` throws, `AgentSkillStrategy` logs it and
  treats that skill as non-matching rather than failing the whole request.
- **Extensibility.** Adding a fifth skill means: implement `ChatSkill`, annotate it
  `@Component`, add a row to this table, and (if it should run before an existing skill) pick a
  `priority()` value between the two neighbors. No changes to the router or controller are
  needed — this is the point of the Strategy pattern here.
- **Gateway selection is a cross-cutting concern, not a skill.** "scan invoice via stripe" and
  "scan invoice via razorpay" are both routed to `ScanInvoiceSkill`; the gateway choice is
  resolved inside that skill by `PaymentGatewayFactory`, not by having separate
  `ScanInvoiceViaStripeSkill` / `ScanInvoiceViaRazorpaySkill` classes.

## Example manifest walkthrough

```
User: "help"
  -> ScanInvoiceSkill.canHandle()  = false (no image attached)
  -> StatusSkill.canHandle()      = false (no "status" keyword)
  -> ReadAloudSkill.canHandle()   = false (no "read aloud"/"speak" keyword)
  -> HelpSkill.canHandle()        = true  -> HelpSkill.handle() runs

User: "scan invoice" + attached invoice.png
  -> ScanInvoiceSkill.canHandle()  = true (trigger phrase + image both present)
  -> ScanInvoiceSkill.handle() runs: OCR -> Parse -> Pay (default gateway) -> TTS

User: "scan invoice via stripe" + attached invoice.png
  -> ScanInvoiceSkill.canHandle()  = true
  -> ScanInvoiceSkill.handle() runs, PaymentGatewayFactory detects "stripe" in the message
     and forces StripeGateway regardless of finvoice.payment.default-gateway

User: "status"
  -> ScanInvoiceSkill.canHandle()  = false (no image)
  -> StatusSkill.canHandle()      = true  -> fetches live status for the user's most recent payment

User: "status order_9f8a7b6c"
  -> StatusSkill.canHandle()      = true  -> fetches status for that specific gateway reference id

User: "read aloud"
  -> ScanInvoiceSkill / StatusSkill both false
  -> ReadAloudSkill.canHandle()   = true  -> re-synthesizes confirmation for the last invoice
```
