package com.bedrock.app.ai;

import org.junit.jupiter.api.Test;

class AiValidationTest {
    @Test void rejectsPrivateCandidateFields() { AiContractChecks.inputPrivacy(); }
    @Test void limitsMutationTargets() { AiContractChecks.candidateBoundary(); }
    @Test void bindsDeleteConfirmationToScope() { AiContractChecks.destructiveConfirmation(); }
    @Test void modelSchemasStayClosed() { AiContractChecks.schemaStructure(); }
    @Test void answersBelongToCurrentQuestions() { AiContractChecks.answerValidation(); }
    @Test void checksActualResponsesAgainstClosedSchemas() { AiContractChecks.strictResponseStructure(); }
    @Test void permitsInclusiveAndUnboundedTimetablesAndRejectsOverflow() { AiContractChecks.timetableAndNumericBounds(); }
    @Test void confirmsPastResolvedDatesOnDelayedAnswers() { AiContractChecks.delayedDateConfirmation(); }
    @Test void malformedCandidateIsAClientValidationError() { AiContractChecks.badCandidateRecurrenceCode(); }
}
