package com.bedrock.app.ocr;

import org.junit.jupiter.api.Test;

class OcrValidationTest {
    @Test void processesAndErasesMemoryUpload() { OcrContractChecks.inMemoryUpload(); }
    @Test void validatesMultipartAndFileTypes() { OcrContractChecks.strictMultipart(); }
    @Test void validatesRecognitionWithoutInventingMissingValues() { OcrContractChecks.recognitionValidation(); }
}
