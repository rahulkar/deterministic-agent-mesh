package com.agentmesh.deterministic.mock;

import com.github.tomakehurst.wiremock.WireMockServer;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

public class MockLiteLlmGateway {
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
        agentStub("compliance_guard_agent", "diagnose", 1,
            "{\"allowed\": false, \"unsupportedTopic\": true, \"reason\": \"Diagnosis requests are outside approved demo policy.\"}");
        agentStub("compliance_guard_agent", "diagnosis", 1,
            "{\"allowed\": false, \"unsupportedTopic\": true, \"reason\": \"Diagnosis requests are outside approved demo policy.\"}");
        agentStub("compliance_guard_agent", "prescribe", 1,
            "{\"allowed\": false, \"unsupportedTopic\": true, \"reason\": \"Prescribing requests are outside approved demo policy.\"}");
        agentStub("compliance_guard_agent", "antibiotic", 1,
            "{\"allowed\": false, \"unsupportedTopic\": true, \"reason\": \"Antibiotic selection requires clinician diagnosis and prescribing guidance.\"}");
        agentStub("compliance_guard_agent", "make me admin", 1,
            "{\"allowed\": false, \"unsupportedTopic\": false, \"reason\": \"Administrative role override is disallowed.\"}");
        agentDefault("compliance_guard_agent", 20,
            "{\"allowed\": true, \"unsupportedTopic\": false, \"reason\": \"Request is within approved medication information policy.\"}");
    }

    private void registerSafetyStubs() {
        agentStub("pharmacovigilance_watchdog", "severe bleeding", 1,
            "{\"adverseEventDetected\": true, \"riskFactor\": \"User mentioned severe bleeding.\"}");
        agentStub("pharmacovigilance_watchdog", "chest pain", 1,
            "{\"adverseEventDetected\": true, \"riskFactor\": \"User mentioned chest pain.\"}");
        agentStub("pharmacovigilance_watchdog", "trouble breathing", 1,
            "{\"adverseEventDetected\": true, \"riskFactor\": \"User mentioned trouble breathing.\"}");
        agentStub("pharmacovigilance_watchdog", "shortness of breath", 1,
            "{\"adverseEventDetected\": true, \"riskFactor\": \"User mentioned shortness of breath.\"}");
        agentStub("pharmacovigilance_watchdog", "high fever", 1,
            "{\"adverseEventDetected\": true, \"riskFactor\": \"User mentioned high fever.\"}");
        agentStub("pharmacovigilance_watchdog", "severe headache", 1,
            "{\"adverseEventDetected\": true, \"riskFactor\": \"User mentioned severe headache.\"}");
        agentStub("pharmacovigilance_watchdog", "head injury", 1,
            "{\"adverseEventDetected\": true, \"riskFactor\": \"User mentioned head injury.\"}");
        agentStub("pharmacovigilance_watchdog", "can't bear weight", 1,
            "{\"adverseEventDetected\": true, \"riskFactor\": \"User mentioned inability to bear weight.\"}");
        agentDefault("pharmacovigilance_watchdog", 20,
            "{\"adverseEventDetected\": false, \"riskFactor\": \"No emergency signal detected.\"}");
    }

    private void registerInteractionStubs() {
        agentStub("drug_interaction_agent", "warfarin", 1,
            "{\"interactionDetected\": true, \"interaction\": \"Aspirin with warfarin can increase bleeding risk.\", \"recommendation\": \"Do not combine aspirin and warfarin without clinician guidance.\"}");
        agentDefault("drug_interaction_agent", 20,
            "{\"interactionDetected\": false, \"interaction\": \"No mocked interaction detected.\", \"recommendation\": \"Use approved medication information only.\"}");
    }

    private void registerDosageStubs() {
        agentStub("dosage_policy_agent", "take the rest", 1,
            "{\"allowed\": false, \"missingRequiredContext\": true, \"reason\": \"Personalized repeat-dose decisions require clinician guidance.\"}");
        agentStub("dosage_policy_agent", "half a tablet", 1,
            "{\"allowed\": false, \"missingRequiredContext\": true, \"reason\": \"Personalized repeat-dose decisions require clinician guidance.\"}");
        agentStub("dosage_policy_agent", "child", 1,
            "{\"allowed\": false, \"missingRequiredContext\": true, \"reason\": \"Pediatric dosing requires clinician-provided age, weight, and indication.\"}");
        agentStub("dosage_policy_agent", "pediatric", 1,
            "{\"allowed\": false, \"missingRequiredContext\": true, \"reason\": \"Pediatric dosing requires clinician-provided age, weight, and indication.\"}");
        agentDefault("dosage_policy_agent", 20,
            "{\"allowed\": true, \"missingRequiredContext\": false, \"reason\": \"No personalized dosing gap detected.\"}");
    }

    private void registerClinicalStubs() {
        agentStub("clinical_retriever", "malformed", 1,
            "{\"approvedText\": \"This response intentionally omits matchFound.\"}");
        agentStub("clinical_retriever", "pregnant", 1,
            "{\"matchFound\": true, \"approvedText\": \"For pregnancy-related medicine questions, use only clinician-approved guidance and consult a healthcare professional.\", \"disclaimer\": \"Pregnancy safety requires clinician review.\"}");
        agentStub("clinical_retriever", "ibuprofen", 2,
            "{\"matchFound\": true, \"approvedText\": \"Ibuprofen information is available only as approved label guidance in this demo.\", \"disclaimer\": \"Consult physician before use.\"}");
        agentStub("clinical_retriever", "paracetamol", 2,
            "{\"matchFound\": true, \"approvedText\": \"Paracetamol information is available only as approved label guidance in this demo.\", \"disclaimer\": \"Consult physician before use.\"}");
        agentStub("clinical_retriever", "acetaminophen", 2,
            "{\"matchFound\": true, \"approvedText\": \"Acetaminophen information is available only as approved label guidance in this demo.\", \"disclaimer\": \"Consult physician before use.\"}");
        agentStub("clinical_retriever", "aspirin", 2,
            "{\"matchFound\": true, \"approvedText\": \"Take 1 tablet (325 mg) every 4 to 6 hours as needed.\", \"disclaimer\": \"Rx Only. Consult physician.\"}");
        agentStub("clinical_retriever", "asprin", 2,
            "{\"matchFound\": true, \"approvedText\": \"Take 1 tablet (325 mg) every 4 to 6 hours as needed.\", \"disclaimer\": \"Rx Only. Consult physician.\"}");
        agentStub("clinical_retriever", "cough", 3,
            "{\"matchFound\": true, \"approvedText\": \"For cough, approved OTC examples include dextromethorphan for cough suppression and guaifenesin for mucus. Read the Drug Facts label and ask a pharmacist or clinician if symptoms are severe, persistent, in a child, or you take other medicines.\", \"disclaimer\": \"General OTC information only; no diagnosis or personalized dosing.\"}");
        agentStub("clinical_retriever", "fever", 3,
            "{\"matchFound\": true, \"approvedText\": \"For fever or aches, approved OTC examples include acetaminophen or ibuprofen when used as directed on the Drug Facts label. Seek medical guidance for high or persistent fever, serious symptoms, pregnancy, children, chronic illness, or medication conflicts.\", \"disclaimer\": \"General OTC information only; follow the product label and clinician guidance.\"}");
        agentStub("clinical_retriever", "sprain", 3,
            "{\"matchFound\": true, \"approvedText\": \"For a mild sprain, rest, ice, compression, and elevation may help. OTC pain relievers such as acetaminophen or NSAIDs may help when used as directed on the Drug Facts label.\", \"disclaimer\": \"Seek medical care for severe pain, deformity, numbness, inability to bear weight, or worsening swelling.\"}");
        agentStub("clinical_retriever", "headache", 3,
            "{\"matchFound\": true, \"approvedText\": \"For headache, approved OTC examples include acetaminophen, ibuprofen, naproxen, or aspirin for some adults when used as directed on the Drug Facts label.\", \"disclaimer\": \"Seek urgent care for sudden severe headache, neurologic symptoms, head injury, fever with stiff neck, or worsening/recurrent pain.\"}");
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
