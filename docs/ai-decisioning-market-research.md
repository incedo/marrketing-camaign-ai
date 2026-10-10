# AI Decisioning — marktonderzoek (2026-10-10)

> Onderzoeksrapport over de AI-decisioning-markt: vendors, open-source projecten en kansen voor een LLM journey planner met een latency-tiered advies-API. Oorspronkelijk opgesteld in het Engels op 2026-10-10.

---

# AI Decisioning & Next-Best-Action Landscape — Research Report
*Research date: 2026-10-10 (sources read same day unless noted). Mission: vendor market, open-source projects, and gaps for an LLM journey planner + latency-tiered advice API (Kotlin Multiplatform journey engine).*
*Confidence flags: `verified live` = read in the research browser today; `index` = from search index results (secondary).*

## Summary
- **Vendor direction (2024–2026):** Every major vendor is moving from predictive decisioning to *agentic* AI: Pega (Customer Engagement Blueprint + Customer Engagement Studio, gen-AI journey design), Salesforce (Journey Decisioning Agent in Winter '26; Agentforce orchestration on Data 360), Adobe (Edge Decisioning API for low-latency inbound, AI offer-ranking models, AI-assisted rule simplification), Braze (Decisioning Studio Go — self-service 1:1 decisioning agent, GA expected Oct 14, 2026), Bloomreach (Loomi marketing agent GA June 2026 — conversational campaign/journey building), Klaviyo (Marketing Agent + Customer Agent, Sept 2025), Insider (Sirius AI — gen-AI segments/journeys/content), Hightouch (AI Decisioning with goals+guardrails; $150M Series D Apr 2026, $2.75B valuation).
- **Open source reality:** No OSS project offers LLM-based journey planning with validation, or a latency-tiered advice API. The closest pieces are: **Dittofeed** (modern OSS journey engine, MIT, very active, but 2025 "LLM generation of journeys" roadmap item shows no evidence of shipping), **Mautic** (flagship OSS marketing automation, GPLv3, Mautic 7 LTS shipped Jan 2026, but financially fragile in 2025 and no AI decisioning), **Apache Unomi** (active in 2026, Apache 2.0, CDP/context server — a profile backend, not a journey orchestrator), **Novu** (MIT, huge community, but notification infrastructure only), **erxes** (AGPLv3 broad XOS suite, no AI decisioning focus).
- **Gap:** An open-source, vendor-neutral, JVM-friendly (Kotlin Multiplatform) module that (a) generates *structurally valid* journeys from natural language via an LLM propose-validate-retry loop, and (b) serves mid-journey decisions through a latency-tiered advice API (ms-tier precomputed cache + seconds-tier live), has no direct open-source equivalent. The only OSS LLM journey builders found are a TELUS POC tied to Adobe's proprietary API and small hobby projects.
- **Build-vs-buy:** Buy = faster time-to-value, prebuilt models, governance/QA tooling, but platform lock-in, usage/contact-based pricing (Salesforce MC $1,500–$30,000/mo), and data leaving the VPC. Build/adopt OSS = data ownership and cost control (the explicit Dittofeed/Mautic value props), but the team owns model training, delivery infra, guardrails, and compliance — exactly the hard parts vendors bundle.

## 1. MARKET — AI Decisioning & Next-Best-Action Vendors

### Pega — the decisioning incumbent going agentic
- **Pega Customer Decision Hub (CDH):** "always-on 'brain' providing centralized AI across all customer touchpoints," delivering hyper-personalized next-best-actions in real time. Forrester (Q2 2025 Wave: AI Decisioning Platforms) named Pega a Leader; Forrester's Q4 2025 Real-Time Interaction Management Wave called it "the top choice for enterprises ready to supercharge their customer-obsessed growth engines with NBA and NBX decisioning capabilities." Positioning: governance + workflow + decisioning in one platform, and explicitly "no per-token pricing" for predictable AI costs. `index` — https://www.businesswire.com/news/home/20260806310096/en/Pega-Named-a-Leader-in-AI-Platforms-by-Independent-Research-Firm and https://www.businesswire.com/news/home/20251117606312/en/Pega-Named-a-Leader-in-Real-Time-Interaction-Management-Software-by-Independent-Research-Firm
- **Moving toward LLM journey planning — yes, most directly of all vendors:** Pega GenAI Blueprint (2024) → **Customer Engagement Blueprint** (Feb 2025): "a generative AI-powered collaboration tool that helps marketing, customer experience, and AI practitioners design customer engagement programs" — builds journey maps and workflows from natural-language prompts; blueprints import into Decision Hub's 1:1 Operations Manager for execution. `index` — https://www.stockwatch.com/News/Item/U-b20250225477319-U!PEGA-20250225/U/PEGA and https://www.cdpinstitute.org/news/pega-unveils-ai-powered-customer-engagement-blueprint/
- **PegaWorld 2026 (June 2026):** previewed **Customer Engagement Studio** (marketing-centric front end to CDH; "deploy agentic-powered marketing campaigns in minutes," planned for Infinity '26 Q3 2026); Blueprint agent builder adds MCP server invocation with governance, and opens Pega data to third-party agents (Anthropic/OpenAI/Google/AWS). `index` — https://www.techtarget.com/enterprise-software/news/366643870/Pegasystems-refines-Blueprint-agent-builder-expands-marketing-tools and https://www.enterprisetimes.co.uk/2026/06/12/pegaworld-2026-focuses-on-outcomes-not-ai-hype/

### Salesforce — Agentforce decisioning on Data 360
- **Marketing Cloud Next / Engagement+:** "Smarter Journey Orchestration in Flow" GA **October 2025** — "build agents that dynamically determine the next best journey or path based on individual customer profiles, behaviors, and context — all without code," plus journey daisy-chaining. **Journey Decisioning Agent** (new in Winter '26): Agentforce selects the optimal journey/flow per customer from real-time behavioral profiles, replacing static conditional branching. `index` — https://www.salesforce.com/blog/next-gen-marketing-cloud-details/?bc=OTH and https://medium.com/@marketingcloudtips/marketing-cloud-next-flow-decisioning-agent-0d0ace1b85e9
- Architecture pattern: Agentforce writes a decision signal (NBA + enroll intent) → Data Cloud real-time segment → consent-aware activation → Marketing Cloud journey sends; consent enforced centrally at the activation. `index` — https://github.com/alexkwitko/salesforce-agentforce/blob/HEAD/skills/salesforce-agentforce/consent-and-marketing-activation.md
- **Notable acquisition:** completed **Informatica acquisition Nov 18, 2025 (~$8B)**; data catalog/integration/governance/MDM folded into **Agentforce 360 / Data 360** to give agents governed, trusted context. `index` — https://www.salesforce.com/uk/news/press-releases/2025/11/18/salesforce-completes-acquisition-of-informatica/?bc=OTH
- Pricing context (build-vs-buy): Marketing Cloud Growth from $1,500/org/mo; Engagement+ $2,000–$30,000/mo; Personalization+ from $15,000/org/mo. `index` — https://www.midcai.com/post/what-is-salesforce-marketing-cloud-next

### Adobe — latency-tiered decisioning already exists (proprietary)
- **Journey Optimizer Decisioning** offers three API tiers that closely mirror the mission's latency-tiered idea: **Decisioning API** (standard), **Edge Decisioning API** ("recommended for inbound use cases... better latency and throughput"), and **Batch Decisioning API** (whole-audience, profile snapshot). `index` — https://experienceleague.adobe.com/en/docs/journey-optimizer/using/decisioning/offer-decisioning/api-reference/offer-delivery-api/start-offer-delivery-apis
- **AI models for offer ranking:** auto-optimization (KPI-driven, non-personalized) and personalized optimization models trained on customer data — i.e., ML decision nodes equivalent to the mission's "ML-decision / next-best-action nodes." `index` — https://experienceleague.adobe.com/en/docs/journey-optimizer/using/decisioning/offer-decisioning/rankings/ai-models/ai-models
- Recent AI motion (2026 release notes): AI detection of simplifiable decisioning rules/ranking formulas (May 2026); decisioning support in Journey **simulation** (Sept 2026); "Analyze Journey Anomalies" skill for the CX Coworker agent; channel rule sets with frequency capping (consent/frequency-guard equivalent). `index` — https://experienceleague.adobe.com/en/docs/journey-optimizer/using/whats-new/previous-rn-new/release-notes-2026
- Adobe has not announced plain-language *journey generation* (its AI stays at offer/content/rules level); journey authoring remains visual/manual.

### Braze — self-service agentic decisioning
- **Forge 2026 (Sept 29, 2026):** launched **BrazeAI Decisioning Studio Go** (self-service 1:1 decisioning; marketer supplies audience + approved variants + guardrails; agent experiments across >1B variant×timing×frequency combinations; beta, GA expected **Oct 14, 2026**), and **Agentic Standards** (AI QA of campaigns/Canvas — setup, copy, links, personalization, compliance — with auto-fix via BrazeAI Operator and human sign-off, audit-logged; GA Oct 2026). Builds on 2025's Decisioning Studio (Pro), BrazeAI Operator, Agent Console. `index` — https://www.businesswire.com/news/home/20260929330225/en/Braze-Accelerates-AI-Innovation-Empowering-Marketers-to-Treat-Every-Customer-Like-Their-Only-Customer and https://relationshipone.com/blog/braze-forge-2026-wrap-up-the-announcements-that-matter-and-what-to-do-about-them/
- **Oct 2026:** strategic collaboration with **AWS** on generative AI and industry-specific engagement solutions. `index` — https://www.cdpinstitute.org/news/pega-unveils-ai-powered-customer-engagement-blueprint/

### Bloomreach — conversational journey building (Loomi)
- **Loomi marketing agent GA (June 2026):** conversational campaign building ("marketers tell the agent what they want to achieve; the agent builds the campaign in minutes"), AI segmentation, on-brand content generation, **journey orchestration + AI timing**, built-in guardrails (review/adjust, audit trails, GDPR). Loomi is its agentic AI platform; 1,400+ brands. `index` — https://www.businesswire.com/news/home/20260602398920/en/Bloomreach-Brings-AI-Agent-to-General-Availability-to-Solve-One-of-Marketings-Most-Expensive-Problems-Keeping-Customers

### Klaviyo — autonomous B2C CRM vision
- **K:BOS (Sept 25, 2025):** **Marketing Agent** (autonomously plans/launches campaigns in minutes, personalizes sends, keeps learning) + **Customer Agent**; positioning as the "AI-first / autonomous B2C CRM." `index` — https://newshub.medianet.com.au/2025/09/klaviyo-advances-ai-first-b2c-crm-with-the-launch-of-marketing-agent-and-customer-agent/120417/
- **June 2025:** AI-powered omnichannel launch (email, SMS/RCS, push, WhatsApp) on Klaviyo Data Platform (2B daily events, 7B+ profiles); K:AI agents "turn a URL into a marketing plan and ready-to-send campaigns"; predictive models for timing, churn prevention, **next best action**; 205,000+ brands. `index` — https://www.stocktitan.net/news/KVYO/klaviyo-rewrites-the-omnichannel-marketing-playbook-for-the-ai-232kczg7662k.html and https://sourceforge.net/software/compare/Bloomreach-vs-Klaviyo/

### Insider — Sirius AI (gen-AI for CX)
- **Sirius AI** (launched ~2023/24): generative + predictive + conversational AI for CX — prompt-generated segments ("30x faster"), **AI-assisted journey development**, content creation, optimization, next-best-channel, best send time; claims 60% marketer productivity gain. Unicorn; 1,200+ enterprises; 4.5B data points/week, 14B+ AI predictions/month. Generative journey assistance (not autonomous planning with validation). `index` — https://www.prnewswire.co.uk/news-releases/insider-announces-sirius-ai-the-worlds-most-comprehensive-generative-ai-solution-for-cx-making-marketing-teams-60-more-productive-301938226.html

### Hightouch — warehouse-native AI Decisioning
- **AI Decisioning:** marketers set goals + guardrails (e.g. "reactivate these churned customers using this creative") and agentic AI + reinforcement learning does 1:1 decisioning — "moves marketers from... rules and manual A/B tests to focusing on strategy." **10B+ AI decisioning events** claimed. `index` — https://pulse2.com/hightouch-profile-tejas-manohar-interview/ and https://cmscritic.com/u-cant-touch-this-cdp-hails-80m-series-c-to-power-its-ai-decisioning
- **Notable raise:** **$150M Series D (Apr 29, 2026), $2.75B valuation** (Goldman Sachs Alternatives, Bain); $100M+ ARR; Leader in 2026 Gartner Magic Quadrant for CDP (first appearance); funding to expand "Agentic Marketing Platform." `index` — https://siliconvalleyinvestclub.com/vi/companies/hightouch/tear-sheet.pdf
- Products: Reverse ETL, Customer Studio, AI Decisioning, **Journeys** (newer, some direct execution via destinations), Events; 200+ destinations; warehouse-first (no proprietary profile store). `index` — https://github.com/coruh-durmus/insider-pm-plugin-marketplace

### Market pattern
The category is converging on the same blueprint the mission describes: **(a) natural-language → journey/campaign** (Pega Blueprint, Bloomreach Loomi, Klaviyo Marketing Agent, Insider Sirius AI) with human review/guardrails, and **(b) per-individual runtime decisioning** (Adobe Edge API, Braze Decisioning Studio Go, Hightouch AI Decisioning, Salesforce Journey Decisioning Agent). All of it is closed/proprietary, usage-priced, and data-hosted by the vendor.

## 2. OPEN SOURCE — Journey Orchestration / Decisioning / Next-Best-Action

| Project | What it does | Architecture (one line) | License | Maturity / activity |
|---|---|---|---|---|
| **Mautic** (mautic/mautic) | Flagship OSS marketing automation: interactive journey/campaign builder (triggers, conditions, decisions, A/B), lead scoring, segments, email/SMS, forms/landing pages, REST API + plugin marketplace | PHP/Symfony + MySQL; self-hosted | **GPLv3**; Acquia publicly committed to keep it GPL ("no current plans to change the license") | Mature: 6.0 'Orion' GA Mar 2025; **7.0 'Columba' GA Jan 2026 as 5-year LTS**; but project reported "most serious financial challenges yet" in 2025 and an uncertain 2026 budget. `index` — https://community.mautic.org (Year in Review 2025), https://mautic.org/blog/acquias-response-to-the-mautic-community-manifesto/, https://www.acquia.com/drupal/mautic-community |
| **Dittofeed** (dittofeed/dittofeed) | Developer-first OSS customer engagement: lifecycle/transactional messaging, visual journey builder, segments, templates; self-hostable Braze/Customer.io alternative | TypeScript + React 19; **Temporal 1.18 durable workflows** for journeys; PostgreSQL + ClickHouse; Docker Compose | **MIT** ("free forever") | Very active: latest **v0.24.0-alpha.12** (Oct 2026 — subscription groups, SES custom endpoints), ~2,700 stars (Mar 2026). `verified live` (releases page) — https://github.com/dittofeed/dittofeed/releases; `index` — https://github.com/v9ai/agentic-sales (2026-03-28 report: ~2,700 stars, 332 forks, channels: email/SMS/push-planned/webhook/WhatsApp-via-webhook) |
| **Apache Unomi** (apache/unomi) | Customer data platform / context server: profile store, event collection, segmentation, personalization & A/B-testing backend | Java + Elasticsearch/OpenSearch; implements OASIS Context Server spec | **Apache 2.0** | **Active, not retired:** multi-tenancy core merged June 2026, CI/build PRs through Aug 2026; ~375 stars; graduated from incubator (unomi.apache.org). It's a profile/CDP backend, *not* a journey orchestrator. `index` — https://github.com/apache/unomi/commit/02ab3c670d46dcab0db8299e53fcbf0159dec4f8, https://github.com/apache/unomi/pull/828 |
| **Novu** (novuhq/novu) | OSS notification infrastructure: workflows-as-code (TS), digest engine, preferences, multi-provider email/SMS/push/in-app/chat | TypeScript; framework SDK + Bridge endpoint; self-hostable | **MIT** | Very active: ~40,131 stars, 22,907 commits, 153 releases (repo read Oct 2026). Notification infra only — no journey orchestration or decisioning. `index` — https://github.com/novuhq/novu |
| **erxes** (erxes/erxes) | "Experience Operating System": marketing automation workflows + CRM, team inbox/chat, sales pipelines, tickets, knowledge base | TypeScript/React; Nx monorepo, plugin architecture, GraphQL Federation; self-hosted | **AGPLv3** (Community); Enterprise proprietary | Active (forks/commits through 2026); broad suite, no AI decisioning focus. `index` — https://github.com/api-evangelist/erxes, https://github.com/batorshih/erxes |
| **Listmonk** | High-performance newsletter/mailing-list manager | Go + SQLite/PostgreSQL | **MIT** | Active; broadcast/newsletter only, not journeys. `index` — https://metaflow.life/blog/open-source-marketing-for-agency-clients |

### Open-source LLM-based journey builders — essentially none
- **j-taggart/ajo-ai-journey-builder:** POC built for TELUS — plain-English description → Claude generates an **Adobe Journey Optimizer** journey spec (segments, events, bilingual messaging, CASL consent gates) → "Create in AJO" pushes a draft via AJO's Authoring API. Target is proprietary (Adobe), config is TELUS-specific, POC maturity. `index` — https://github.com/j-taggart/ajo-ai-journey-builder
- **saurabhyabx/user_journey_builder:** hobby app — interview → LLM generates journey nodes/connections into a DB; last updated ~early 2026; tiny scale. `index` — https://github.com/saurabhyabx/user_journey_builder/blob/HEAD/implementation.md
- **Dittofeed's roadmap** (2025) listed "LLM integration — drive quicker, easier generation of journeys, segments, and templates" as *planned*; **no evidence it has shipped** (checked release notes through v0.24.0-alpha, Oct 2026). See Could not verify.
- Everything else found under this label is mislabeled: LLM training curricula, journalist-finder agents, learning repos.

## 3. GAPS — What an LLM Journey Planner + Latency-Tiered Advice API Could Fill

1. **No OSS LLM journey planner with validation.** Vendors (Pega Blueprint, Bloomreach Loomi, Klaviyo Marketing Agent) all generate campaigns/journeys from natural language, but their "validate" step is human review in their UI. No open-source project implements the **propose → validate → retry** loop that guarantees only structurally valid journey graphs ship — the mission's core differentiator vs. both vendors and OSS.
2. **No OSS latency-tiered advice API.** The exact pattern — ms-tier precomputed/cache decisions for web visitors vs. seconds-tier live decisions for email — exists only in proprietary form: Adobe's **Edge Decisioning API** vs. standard/batch APIs; Braze's real-time decisioning; Hightouch's decision-time ML. Dittofeed/Mautic run journeys on workflow engines (Temporal/cron), not per-individual real-time decision endpoints.
3. **No OSS ML/RL next-best-action runtime.** Vendors ship trained models (Adobe auto-/personalized optimization, Hightouch agentic+RL decisioning, Braze Decisioning Studio Go). OSS offers rule-based decisions (Mautic conditions), A/B tests, and predictive lead scoring at best. An OSS NBA runtime — especially with pluggable models, guardrails (frequency/consent), and explainability — would fill a genuine hole.
4. **JVM/Kotlin-shaped hole.** The OSS landscape is PHP (Mautic), Java-legacy (Unomi), or TypeScript (Dittofeed, Novu, erxes). A **Kotlin Multiplatform** journey engine is novel: embeddable in JVM backends and potentially reusable in mobile contexts, with a runtime API an external executor calls mid-journey — an architecture no OSS journey tool currently offers (all OSS options bundle planner + executor + delivery).
5. **Data-ownership + cost pressure favor OSS.** Vendor pricing is usage/contact-based and rising (Salesforce MC up to $30k/mo; Mautic users cite "always free... total control"). Mautic's financial fragility (2025) and Dittofeed's missing LLM roadmap item create an opening: a focused, well-scoped OSS module (planner + advice API, *not* another full marketing cloud) avoids competing head-on with Mautic/Dittofeed and instead complements them as a decisioning layer any executor (including Dittofeed's Temporal engine or Mautic) could call.
6. **Trust/governance is the adoption gate.** Every vendor announcement pairs agentic generation with guardrails: Braze Agentic Standards (QA + human sign-off), Bloomreach guardrails, Pega MCP governance, Adobe rule simplification. An OSS project should lead with the same: deterministic validation, consent/frequency guards as first-class node types, audit logs, and human-in-the-loop — matching enterprise buyer expectations.

### Build-vs-buy considerations
- **Buy when:** speed matters (campaigns live in weeks), you need prebuilt models/audiences, compliance certifications, or vendor SLAs; accept lock-in, per-contact/per-message pricing, and customer data in vendor clouds.
- **Build/adopt-OSS when:** data residency is non-negotiable (the explicit Dittofeed/Mautic value prop: PII stays in your VPC), volumes make usage pricing punitive, or you need custom decision logic vendors don't expose. Budget for: model training/eval, delivery infrastructure, guardrail engineering, and ongoing maintenance — the parts vendors bundle.
- **The hybrid fit for this project:** an OSS planner+advice module is a *complement*, not a replacement — it can sit in front of Mautic/Dittofeed (journey authoring/execution) and beside Novu (delivery), giving a team vendor-grade decisioning without migrating platforms.

## Could not verify
- **Dittofeed LLM journey generation:** 2025 roadmaps list it as planned/unchecked; release notes through v0.24.0-alpha (Oct 2026, read live) show no LLM feature. Could not confirm whether it is in active development or dropped.
- **Pega Customer Engagement Studio:** previewed June 2026, "slated for Q3 2026" per TechTarget; GA status unconfirmed.
- **Braze Decisioning Studio Go / Agentic Standards:** GA expected Oct 14, 2026 (just after research date) — unconfirmed whether it shipped on schedule.
- **Insider Sirius AI:** most detailed sources are from its 2023/24 launch; no 2025–2026 capability updates verified.
- **Star counts** for Dittofeed (~2,700) are from a March 2026 third-party report; Mautic's star count and Dittofeed's current user base were not verified.

## Sources
*All URLs copied verbatim from tool results; read 2026-10-10.*
- https://www.businesswire.com/news/home/20260806310096/en/Pega-Named-a-Leader-in-AI-Platforms-by-Independent-Research-Firm (index)
- https://www.businesswire.com/news/home/20251117606312/en/Pega-Named-a-Leader-in-Real-Time-Interaction-Management-Software-by-Independent-Research-Firm (index)
- https://www.techtarget.com/enterprise-software/news/366643870/Pegasystems-refines-Blueprint-agent-builder-expands-marketing-tools (index)
- https://www.enterprisetimes.co.uk/2026/06/12/pegaworld-2026-focuses-on-outcomes-not-ai-hype/ (index)
- https://www.stockwatch.com/News/Item/U-b20250225477319-U!PEGA-20250225/U/PEGA (index)
- https://www.cdpinstitute.org/news/pega-unveils-ai-powered-customer-engagement-blueprint/ (index)
- https://www.salesforce.com/blog/next-gen-marketing-cloud-details/?bc=OTH (index)
- https://medium.com/@marketingcloudtips/marketing-cloud-next-flow-decisioning-agent-0d0ace1b85e9 (index)
- https://github.com/alexkwitko/salesforce-agentforce/blob/HEAD/skills/salesforce-agentforce/consent-and-marketing-activation.md (index)
- https://www.salesforce.com/uk/news/press-releases/2025/11/18/salesforce-completes-acquisition-of-informatica/?bc=OTH (index)
- https://www.techtarget.com/enterprise-software/news/366636353/Salesforce-adds-Informatica-to-Data-360-MuleSoft-fold (index)
- https://www.midcai.com/post/what-is-salesforce-marketing-cloud-next (index)
- https://experienceleague.adobe.com/en/docs/journey-optimizer/using/decisioning/offer-decisioning/api-reference/offer-delivery-api/start-offer-delivery-apis (index)
- https://experienceleague.adobe.com/en/docs/journey-optimizer/using/decisioning/offer-decisioning/rankings/ai-models/ai-models (index)
- https://experienceleague.adobe.com/en/docs/journey-optimizer/using/whats-new/previous-rn-new/release-notes-2026 (index)
- https://www.businesswire.com/news/home/20260929330225/en/Braze-Accelerates-AI-Innovation-Empowering-Marketers-to-Treat-Every-Customer-Like-Their-Only-Customer (index)
- https://relationshipone.com/blog/braze-forge-2026-wrap-up-the-announcements-that-matter-and-what-to-do-about-them/ (index)
- https://www.businesswire.com/news/home/20260602398920/en/Bloomreach-Brings-AI-Agent-to-General-Availability-to-Solve-One-of-Marketings-Most-Expensive-Problems-Keeping-Customers (index)
- https://newshub.medianet.com.au/2025/09/klaviyo-advances-ai-first-b2c-crm-with-the-launch-of-marketing-agent-and-customer-agent/120417/ (index)
- https://www.stocktitan.net/news/KVYO/klaviyo-rewrites-the-omnichannel-marketing-playbook-for-the-ai-232kczg7662k.html (index)
- https://sourceforge.net/software/compare/Bloomreach-vs-Klaviyo/ (index)
- https://www.prnewswire.co.uk/news-releases/insider-announces-sirius-ai-the-worlds-most-comprehensive-generative-ai-solution-for-cx-making-marketing-teams-60-more-productive-301938226.html (index)
- https://siliconvalleyinvestclub.com/vi/companies/hightouch/tear-sheet.pdf (index)
- https://pulse2.com/hightouch-profile-tejas-manohar-interview/ (index)
- https://cmscritic.com/u-cant-touch-this-cdp-hails-80m-series-c-to-power-its-ai-decisioning (index; URL verbatim from search output — note: the live "Full URLs" mapping for the cmscritic result was `https://cmscritic.com/u-cant-touch-this-cdp-hails-80m-series-c-to-power-its-ai-decisioning` in the search-results text; the exact-Full-URL list showed the same path — treating as index)
- https://github.com/coruh-durmus/insider-pm-plugin-marketplace (index)
- https://community.mautic.org — Project Lead's Report: Year in Review 2025 (index)
- https://mautic.org/blog/acquias-response-to-the-mautic-community-manifesto/ (index)
- https://www.acquia.com/drupal/mautic-community (index)
- https://github.com/dittofeed/dittofeed/releases (**verified live** 2026-10-10)
- https://github.com/v9ai/agentic-sales (index; contains docs/ai-features/dittofeed.md, research dated 2026-03-28)
- https://github.com/apache/unomi/commit/02ab3c670d46dcab0db8299e53fcbf0159dec4f8 (index)
- https://github.com/apache/unomi/pull/828 (index)
- https://github.com/novuhq/novu (index)
- https://github.com/getbeton/beton-main-site/blob/HEAD/src/data/blog/novu-pricing-teardown.md (index)
- https://github.com/api-evangelist/erxes (index)
- https://github.com/batorshih/erxes (index)
- https://metaflow.life/blog/open-source-marketing-for-agency-clients (index)
- https://github.com/j-taggart/ajo-ai-journey-builder (index)
- https://github.com/saurabhyabx/user_journey_builder/blob/HEAD/implementation.md (index)

### Notes files (research directory)
- notes/source-01-pega.md, notes/source-02-salesforce-adobe.md, notes/source-03-oss-basics.md, notes/source-04-braze-bloomreach-klaviyo-hightouch.md, notes/source-05-insider-mautic-dittofeed-status.md, notes/source-06-novu-erxes-informatica.md

### Page-instruction disclosures
- None: no page encountered during research issued instructions attempting to redirect the mission, extract data, or authorize actions. (The github.com releases fetch and all searches were read-only.)
