package com.agentmesh.deterministic.mock;

import com.github.tomakehurst.wiremock.WireMockServer;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

public class MockLiteLlmGateway {
    private static final String PREGNANCY_CONTENT =
        "{\"matchFound\": true, \"approvedText\": \"For pregnancy-related medicine questions, use only clinician-approved guidance and consult a healthcare professional.\", \"disclaimer\": \"Pregnancy safety requires clinician review.\"}";
    private static final String PREGNANCY_FEVER_CONTENT =
        "{\"matchFound\": true, \"approvedText\": \"For fever during pregnancy, this demo only provides approved guidance to contact a clinician or pharmacist before choosing an OTC medicine. Seek urgent care for high, persistent, or concerning fever symptoms.\", \"disclaimer\": \"Pregnancy and fever require clinician-specific guidance.\"}";
    private static final String IBUPROFEN_CONTENT =
        "{\"matchFound\": true, \"approvedText\": \"Ibuprofen information is available only as approved Drug Facts label guidance in this demo. Do not use it as a substitute for clinician advice, especially with pregnancy, kidney disease, ulcers, blood thinners, or other medical conditions.\", \"disclaimer\": \"General OTC information only; follow the product label and clinician guidance.\"}";
    private static final String ACETAMINOPHEN_CONTENT =
        "{\"matchFound\": true, \"approvedText\": \"Acetaminophen information is available only as approved Drug Facts label guidance in this demo. Avoid taking more than one acetaminophen-containing product at the same time unless a clinician says to do so.\", \"disclaimer\": \"General OTC information only; follow the product label and clinician guidance.\"}";
    private static final String ACETAMINOPHEN_LIVER_ALCOHOL_CONTENT =
        "{\"matchFound\": true, \"approvedText\": \"Acetaminophen questions involving liver disease or alcohol use require clinician or pharmacist guidance because overdose or combined products can create serious risk.\", \"disclaimer\": \"General OTC information only; do not use this as personalized dosing advice.\"}";
    private static final String ASPIRIN_CONTENT =
        "{\"matchFound\": true, \"approvedText\": \"Aspirin information is available only as approved label guidance in this demo. Some aspirin products list 325 mg tablets; follow the Drug Facts label and avoid use when contraindications or bleeding risks apply.\", \"disclaimer\": \"General OTC information only; consult a clinician for personal use questions.\"}";
    private static final String NAPROXEN_CONTENT =
        "{\"matchFound\": true, \"approvedText\": \"Naproxen information is available only as approved Drug Facts label guidance in this demo. Avoid combining NSAIDs unless a clinician instructs you to do so.\", \"disclaimer\": \"General OTC information only; follow the product label and clinician guidance.\"}";
    private static final String COUGH_CONTENT =
        "{\"matchFound\": true, \"approvedText\": \"For cough, approved OTC examples include dextromethorphan for cough suppression and guaifenesin for mucus. Read the Drug Facts label and ask a pharmacist or clinician if symptoms are severe, persistent, in a child, or you take other medicines.\", \"disclaimer\": \"General OTC information only; no diagnosis or personalized dosing.\"}";
    private static final String COLD_CONGESTION_CONTENT =
        "{\"matchFound\": true, \"approvedText\": \"For cold, mucus, or congestion symptoms, approved OTC examples may include expectorants or cough suppressants depending on the symptom. Read the Drug Facts label and ask a pharmacist or clinician if symptoms are severe, persistent, or involve a child.\", \"disclaimer\": \"General OTC information only; no diagnosis or personalized dosing.\"}";
    private static final String FEVER_CONTENT =
        "{\"matchFound\": true, \"approvedText\": \"For fever or aches, approved OTC examples include acetaminophen or ibuprofen when used as directed on the Drug Facts label. Seek medical guidance for high or persistent fever, serious symptoms, pregnancy, children, chronic illness, or medication conflicts.\", \"disclaimer\": \"General OTC information only; follow the product label and clinician guidance.\"}";
    private static final String SPRAIN_CONTENT =
        "{\"matchFound\": true, \"approvedText\": \"For a mild sprain or strain, rest, ice, compression, and elevation may help. OTC pain relievers such as acetaminophen or NSAIDs may help when used as directed on the Drug Facts label.\", \"disclaimer\": \"Seek medical care for severe pain, deformity, numbness, inability to bear weight, or worsening swelling.\"}";
    private static final String HEADACHE_CONTENT =
        "{\"matchFound\": true, \"approvedText\": \"For headache, approved OTC examples include acetaminophen, ibuprofen, naproxen, or aspirin for some adults when used as directed on the Drug Facts label.\", \"disclaimer\": \"Seek urgent care for sudden severe headache, neurologic symptoms, head injury, fever with stiff neck, or worsening/recurrent pain.\"}";

    private final WireMockServer wireMockServer;

    public MockLiteLlmGateway(int port) {
        this.wireMockServer = new WireMockServer(port);
    }

    public void start() {
        wireMockServer.start();
        configureFor("localhost", wireMockServer.port());
        registerGreetingStubs();
        registerComplianceStubs();
        registerSafetyStubs();
        registerInteractionStubs();
        registerDosageStubs();
        registerClinicalStubs();
        registerFallbackStub();
    }

    public void stop() {
        wireMockServer.stop();
    }

    public int countRequests() {
        return wireMockServer.getAllServeEvents().size();
    }

    public String baseUrl() {
        return "http://localhost:" + wireMockServer.port();
    }

    private void registerGreetingStubs() {
        agentDefault("greeting_agent", 20,
            "{\"handled\": true, \"message\": \"Hi. I can help with approved OTC medication information and safety triage questions in this demo.\", \"disclaimer\": \"I cannot provide diagnosis or personalized dosing.\"}");
    }

    private void registerComplianceStubs() {
        agentStubs("compliance_guard_agent", 1,
            "{\"allowed\": false, \"unsupportedTopic\": true, \"reason\": \"Diagnosis requests are outside approved demo policy.\"}",
            "diagnose", "diagnosis");
        agentStubs("compliance_guard_agent", 1,
            "{\"allowed\": false, \"unsupportedTopic\": true, \"reason\": \"Prescribing requests are outside approved demo policy.\"}",
            "prescribe", "prescription");
        agentStubs("compliance_guard_agent", 1,
            "{\"allowed\": false, \"unsupportedTopic\": true, \"reason\": \"Antibiotic selection requires clinician diagnosis and prescribing guidance.\"}",
            "antibiotic", "need antibiotics");
        agentStub("compliance_guard_agent", "make me admin", 1,
            "{\"allowed\": false, \"unsupportedTopic\": false, \"reason\": \"Administrative role override is disallowed.\"}");
        agentDefault("compliance_guard_agent", 20,
            "{\"allowed\": true, \"unsupportedTopic\": false, \"reason\": \"Request is within approved medication information policy.\"}");
    }

    private void registerSafetyStubs() {
        agentStubs("pharmacovigilance_watchdog", 1,
            "{\"adverseEventDetected\": true, \"riskFactor\": \"User mentioned severe bleeding.\"}",
            "severe bleeding", "bleeding badly");
        agentStubs("pharmacovigilance_watchdog", 1,
            "{\"adverseEventDetected\": true, \"riskFactor\": \"User mentioned chest pain.\"}",
            "chest pain");
        agentStubs("pharmacovigilance_watchdog", 1,
            "{\"adverseEventDetected\": true, \"riskFactor\": \"User mentioned trouble breathing.\"}",
            "trouble breathing", "difficulty breathing", "shortness of breath");
        agentStubs("pharmacovigilance_watchdog", 1,
            "{\"adverseEventDetected\": true, \"riskFactor\": \"User mentioned high fever.\"}",
            "high fever");
        agentStubs("pharmacovigilance_watchdog", 1,
            "{\"adverseEventDetected\": true, \"riskFactor\": \"User mentioned severe headache.\"}",
            "severe headache");
        agentStubs("pharmacovigilance_watchdog", 1,
            "{\"adverseEventDetected\": true, \"riskFactor\": \"User mentioned head injury.\"}",
            "head injury");
        agentStubs("pharmacovigilance_watchdog", 1,
            "{\"adverseEventDetected\": true, \"riskFactor\": \"User mentioned inability to bear weight.\"}",
            "can't bear weight", "unable to bear weight");
        agentStubs("pharmacovigilance_watchdog", 1,
            "{\"adverseEventDetected\": true, \"riskFactor\": \"User mentioned possible allergic reaction.\"}",
            "allergic reaction");
        agentStubs("pharmacovigilance_watchdog", 1,
            "{\"adverseEventDetected\": true, \"riskFactor\": \"User mentioned overdose concern.\"}",
            "overdose");
        agentDefault("pharmacovigilance_watchdog", 20,
            "{\"adverseEventDetected\": false, \"riskFactor\": \"No emergency signal detected.\"}");
    }

    private void registerInteractionStubs() {
        agentStubs("drug_interaction_agent", 1,
            "{\"interactionDetected\": true, \"interaction\": \"Aspirin with warfarin can increase bleeding risk.\", \"recommendation\": \"Do not combine aspirin and warfarin without clinician guidance.\"}",
            "warfarin");
        agentStubs("drug_interaction_agent", 1,
            "{\"interactionDetected\": true, \"interaction\": \"Aspirin or NSAIDs with blood thinners can increase bleeding risk.\", \"recommendation\": \"Ask a clinician or pharmacist before combining pain relievers with anticoagulants.\"}",
            "blood thinner", "anticoagulant", "coumadin");
        agentDefault("drug_interaction_agent", 20,
            "{\"interactionDetected\": false, \"interaction\": \"No mocked interaction detected.\", \"recommendation\": \"Use approved medication information only.\"}");
    }

    private void registerDosageStubs() {
        agentStubs("dosage_policy_agent", 1,
            "{\"allowed\": false, \"missingRequiredContext\": true, \"reason\": \"Personalized repeat-dose decisions require clinician guidance.\"}",
            "take the rest", "half a tablet", "repeat dose");
        agentStubAll("dosage_policy_agent", 1,
            "{\"allowed\": false, \"missingRequiredContext\": true, \"reason\": \"Fever questions for a child require clinician-guided age, weight, duration, and symptom context.\"}",
            "child", "fever");
        agentStubs("dosage_policy_agent", 1,
            "{\"allowed\": false, \"missingRequiredContext\": true, \"reason\": \"Pediatric dosing requires clinician-provided age, weight, and indication.\"}",
            "child", "kid", "pediatric", "by weight");
        agentDefault("dosage_policy_agent", 20,
            "{\"allowed\": true, \"missingRequiredContext\": false, \"reason\": \"No personalized dosing gap detected.\"}");
    }

    private void registerClinicalStubs() {
        agentStub("clinical_retriever", "malformed", 1,
            "{\"approvedText\": \"This response intentionally omits matchFound.\"}");
        agentStubAll("clinical_retriever", 1, PREGNANCY_FEVER_CONTENT, "pregnant", "fever");
        agentStubAll("clinical_retriever", 1, ACETAMINOPHEN_LIVER_ALCOHOL_CONTENT, "acetaminophen", "alcohol");
        agentStubAll("clinical_retriever", 1, ACETAMINOPHEN_LIVER_ALCOHOL_CONTENT, "acetaminophen", "liver");
        agentStubs("clinical_retriever", 2, PREGNANCY_CONTENT, "pregnant", "pregnancy", "pregrant", "pregant", "pregnent", "pregnnt");
        agentStubs("clinical_retriever", 2, IBUPROFEN_CONTENT, "ibuprofen", "advil", "motrin", "ibuprofin");
        agentStubs("clinical_retriever", 2, ACETAMINOPHEN_CONTENT, "acetaminophen", "paracetamol", "paracetemol", "tylenol");
        agentStubs("clinical_retriever", 2, ASPIRIN_CONTENT, "aspirin", "asprin", "asa", "baby aspirin", "acetylsalicylic acid");
        agentStubs("clinical_retriever", 2, NAPROXEN_CONTENT, "naproxen", "naproxin", "aleve");
        agentStubs("clinical_retriever", 3, COUGH_CONTENT, "cough", "caugh", "dextromethorphan", "dxm", "guaifenesin");
        agentStubs("clinical_retriever", 3, COLD_CONGESTION_CONTENT, "cold", "mucus", "congestion", "expectorant", "mucus relief");
        agentStubs("clinical_retriever", 3, FEVER_CONTENT, "fever", "feaver", "aches");
        agentStubs("clinical_retriever", 3, SPRAIN_CONTENT, "sprain", "strain");
        agentStubs("clinical_retriever", 3, HEADACHE_CONTENT, "headache", "headach");
        agentDefault("clinical_retriever", 20,
            "{\"matchFound\": false, \"approvedText\": \"\", \"disclaimer\": \"No approved clinical content found.\"}");
    }

    private void registerFallbackStub() {
        stubFor(post(urlEqualTo("/v1/chat/completions"))
            .atPriority(100)
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(openAiResponse("{\"matchFound\": false, \"approvedText\": \"\", \"disclaimer\": \"No stub matched.\"}"))));
    }

    private void agentStub(String agentName, String promptFragment, int priority, String content) {
        stubFor(post(urlEqualTo("/v1/chat/completions"))
            .atPriority(priority)
            .withRequestBody(containing("agent=" + agentName))
            .withRequestBody(containing(promptFragment))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(openAiResponse(content))));
    }

    private void agentStubs(String agentName, int priority, String content, String... promptFragments) {
        for (String promptFragment : promptFragments) {
            agentStub(agentName, promptFragment, priority, content);
        }
    }

    private void agentStubAll(String agentName, int priority, String content, String... promptFragments) {
        var mapping = post(urlEqualTo("/v1/chat/completions"))
            .atPriority(priority)
            .withRequestBody(containing("agent=" + agentName));
        for (String promptFragment : promptFragments) {
            mapping.withRequestBody(containing(promptFragment));
        }
        stubFor(mapping.willReturn(aResponse()
            .withHeader("Content-Type", "application/json")
            .withBody(openAiResponse(content))));
    }

    private void agentDefault(String agentName, int priority, String content) {
        stubFor(post(urlEqualTo("/v1/chat/completions"))
            .atPriority(priority)
            .withRequestBody(containing("agent=" + agentName))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(openAiResponse(content))));
    }

    private String openAiResponse(String content) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"");
        return """
            {
              "choices": [{
                "message": {
                  "content": "%s"
                }
              }]
            }
            """.formatted(escaped);
    }
}
