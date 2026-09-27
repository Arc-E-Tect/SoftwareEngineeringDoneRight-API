package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T19.6: each contract of the contract-case corpus, a derivation rule or a coverage reason each,
 * derives exactly these cases, each in one line -- class, id, kind, status, path values,
 * content type and headers -- and exactly this response coverage.
 */
@DisplayName("T19.6 A corpus per kind and reason")
class ContractCaseCorpusTest {

    @TempDir
    Path directory;

    private void assertCorpus(String name, List<String> cases, List<String> coverage) {
        ValidValueFixtures.Fixture f = ContractCaseFixtures.corpus(name, directory);
        assertThat(ContractCaseFixtures.cases(f.report()).stream().map(ContractCaseFixtures::line).toList())
                .containsExactlyElementsOf(cases);
        List<String> recorded = new java.util.ArrayList<>();
        f.report().get("responseCoverage").forEach(e -> recorded.add(ContractCaseFixtures.coverageLine(e)));
        assertThat(recorded).containsExactlyElementsOf(coverage);
    }

    @Test
    void success() {
        assertCorpus("success", List.of(
                "GetPlainContractCases success-200-required SUCCESS 200 path[] - [Accept=application/json]",
                "PostMaybeContractCases success-204-required SUCCESS 204 path[] application/json []",
                "PostMaybeContractCases success-204-full SUCCESS 204 path[] application/json []",
                "PostMaybeContractCases success-204-no-body SUCCESS 204 path[] - []",
                "PostOptionalContractCases success-201-required SUCCESS 201 path[] application/json [Accept=application/json]",
                "PostOptionalContractCases success-201-full SUCCESS 201 path[] application/json [Accept=application/json]",
                "PostPatternedPeriodContractCases success-201-required SUCCESS 201 path[] application/json []",
                "PostPatternedPeriodContractCases success-201-full SUCCESS 201 path[] application/json []",
                "PostPeriodContractCases success-201-required SUCCESS 201 path[] application/json []",
                "PutEitherContractCases success-200-required SUCCESS 200 path[a] application/json []",
                "PutEitherContractCases success-200-full SUCCESS 200 path[a] application/json []",
                "PutEitherContractCases success-201-required SUCCESS 201 path[a] application/json []",
                "PutEitherContractCases success-201-full SUCCESS 201 path[a] application/json []"), List.of(
                "PostOptionalContractCases 201 [success-201-required, success-201-full]",
                "GetPlainContractCases 200 [success-200-required]",
                "PutEitherContractCases 200 [success-200-required, success-200-full]",
                "PutEitherContractCases 201 [success-201-required, success-201-full]",
                "PostMaybeContractCases 204 [success-204-required, success-204-full, success-204-no-body]",
                "PostImpossibleContractCases 201 NO_VALID_VALUE",
                "PostPeriodContractCases 201 [success-201-required]",
                "PostDurationContractCases 201 NO_VALID_VALUE",
                "PostPatternedPeriodContractCases 201 [success-201-required, success-201-full]"));
    }

    @Test
    void notFound() {
        assertCorpus("not-found", List.of(
                "GetCollectionContractCases success-200-required SUCCESS 200 path[] - []",
                "GetItemContractCases success-200-required SUCCESS 200 path[aaa] - [Accept=application/problem+json]",
                "GetItemContractCases not-found NOT_FOUND 404 path[aab] - [Accept=application/problem+json]",
                "GetPairContractCases success-200-required SUCCESS 200 path[1, 00000000-0000-4000-8000-000000000000] - []",
                "GetPairContractCases not-found NOT_FOUND 404 path[2, 00000000-0000-4000-8000-000000000001] - []",
                "GetSingleContractCases success-200-required SUCCESS 200 path[only] - []"), List.of(
                "GetItemContractCases 200 [success-200-required]",
                "GetItemContractCases 404 [not-found]",
                "GetSingleContractCases 200 [success-200-required]",
                "GetSingleContractCases 404 NOT_FOUND_WITHOUT_SECOND_VALUE",
                "GetCollectionContractCases 200 [success-200-required]",
                "GetCollectionContractCases 404 NOT_FOUND_WITHOUT_PATH_PARAMETER",
                "GetPairContractCases 200 [success-200-required]",
                "GetPairContractCases 404 [not-found]"));
    }

    @Test
    void negotiation() {
        assertCorpus("negotiation", List.of(
                "GetAnythingContractCases success-200-required SUCCESS 200 path[] - [Accept=*/*]",
                "GetCandidatesContractCases success-200-required SUCCESS 200 path[] - "
                        + "[Accept=application/vnd.apionly.not-acceptable, text/vnd.apionly.not-acceptable]",
                "GetEmptyContractCases success-204-required SUCCESS 204 path[] - []",
                "GetNobodyContractCases success-200-required SUCCESS 200 path[] - []",
                "GetReportContractCases success-200-required SUCCESS 200 path[] - "
                        + "[Accept=application/json, application/problem+json]",
                "GetReportContractCases not-acceptable NOT_ACCEPTABLE 406 path[] - "
                        + "[Accept=application/vnd.apionly.not-acceptable]",
                "GetTypedContractCases success-200-required SUCCESS 200 path[] - "
                        + "[Accept=application/*, text/*; charset=utf-8]",
                "PostCandidatesContractCases success-202-required SUCCESS 202 path[] application/json []",
                "PostRangedContractCases success-202-required SUCCESS 202 path[] application/json []",
                "PostUploadContractCases success-202-required SUCCESS 202 path[] application/json []",
                "PostUploadContractCases unsupported-media-type UNSUPPORTED_MEDIA_TYPE 415 path[] text/plain []",
                "PostWideContractCases success-202-required SUCCESS 202 path[] application/json []"), List.of(
                "GetReportContractCases 200 [success-200-required]",
                "GetReportContractCases 406 [not-acceptable]",
                "GetAnythingContractCases 200 [success-200-required]",
                "GetAnythingContractCases 406 NO_MEDIA_TYPE_LEFT",
                "GetTypedContractCases 200 [success-200-required]",
                "GetTypedContractCases 406 NO_MEDIA_TYPE_LEFT",
                "GetCandidatesContractCases 200 [success-200-required]",
                "GetCandidatesContractCases 406 NO_MEDIA_TYPE_LEFT",
                "PostCandidatesContractCases 202 [success-202-required]",
                "PostCandidatesContractCases 415 NO_MEDIA_TYPE_LEFT",
                "GetEmptyContractCases 204 [success-204-required]",
                "GetEmptyContractCases 406 NO_MEDIA_TYPE_LEFT",
                "PostUploadContractCases 202 [success-202-required]",
                "PostUploadContractCases 415 [unsupported-media-type]",
                "PostWideContractCases 202 [success-202-required]",
                "PostWideContractCases 415 NO_MEDIA_TYPE_LEFT",
                "PostRangedContractCases 202 [success-202-required]",
                "PostRangedContractCases 415 NO_MEDIA_TYPE_LEFT",
                "GetNobodyContractCases 200 [success-200-required]",
                "GetNobodyContractCases 415 UNSUPPORTED_WITHOUT_BODY"));
    }

    /** The statuses operation's coverage, which no setting changes. */
    private static final List<String> STATUSES = List.of(
                "GetStatusesContractCases 200 [success-200-required]",
                "GetStatusesContractCases 301 BEHAVIOUR_OR_STATE",
                "GetStatusesContractCases 400 INVALID_REQUEST_STATUS",
                "GetStatusesContractCases 401 BEHAVIOUR_OR_STATE",
                "GetStatusesContractCases 403 BEHAVIOUR_OR_STATE",
                "GetStatusesContractCases 409 BEHAVIOUR_OR_STATE",
                "GetStatusesContractCases 418 BEHAVIOUR_OR_STATE",
                "GetStatusesContractCases 422 BEHAVIOUR_OR_STATE",
                "GetStatusesContractCases 429 BEHAVIOUR_OR_STATE",
                "GetStatusesContractCases 503 BEHAVIOUR_OR_STATE",
                "GetStatusesContractCases 4XX RANGE_OR_DEFAULT",
                "GetStatusesContractCases default RANGE_OR_DEFAULT");

    private static List<String> concat(List<String> a, String... b) {
        List<String> out = new java.util.ArrayList<>(a);
        out.addAll(List.of(b));
        return out;
    }

    @Test
    void coverage() {
        assertCorpus("coverage", List.of(
                "GetCheckedContractCases success-200-required SUCCESS 200 path[] - []",
                "GetCheckedContractCases query-limit-required INVALID_REQUEST 400 path[] - []",
                "GetCheckedContractCases query-limit-type INVALID_REQUEST 400 path[] - []",
                "GetCheckedContractCases query-limit-minimum INVALID_REQUEST 400 path[] - []",
                "GetStatusesContractCases success-200-required SUCCESS 200 path[] - []"), concat(STATUSES,
                "GetCheckedContractCases 200 [success-200-required]",
                "GetCheckedContractCases 400 [query-limit-required, query-limit-type, query-limit-minimum]",
                "PostDegradedContractCases 201 INSIDE_DEGRADED_CONSTRUCT"));
    }

    @Test
    void kindsSwitchedOff() {
        assertCorpus("coverage-success-only", List.of(
                "GetCheckedContractCases success-200-required SUCCESS 200 path[] - []",
                "GetStatusesContractCases success-200-required SUCCESS 200 path[] - []"), concat(STATUSES,
                "GetCheckedContractCases 200 [success-200-required]",
                "GetCheckedContractCases 400 KIND_SWITCHED_OFF",
                "PostDegradedContractCases 201 INSIDE_DEGRADED_CONSTRUCT"));
        assertCorpus("not-found-without-not-found", List.of(
                "GetCollectionContractCases success-200-required SUCCESS 200 path[] - []",
                "GetItemContractCases success-200-required SUCCESS 200 path[aaa] - [Accept=application/problem+json]",
                "GetPairContractCases success-200-required SUCCESS 200 path[1, 00000000-0000-4000-8000-000000000000] - []",
                "GetSingleContractCases success-200-required SUCCESS 200 path[only] - []"), List.of(
                "GetItemContractCases 200 [success-200-required]",
                "GetItemContractCases 404 KIND_SWITCHED_OFF",
                "GetSingleContractCases 200 [success-200-required]",
                "GetSingleContractCases 404 NOT_FOUND_WITHOUT_SECOND_VALUE",
                "GetCollectionContractCases 200 [success-200-required]",
                "GetCollectionContractCases 404 NOT_FOUND_WITHOUT_PATH_PARAMETER",
                "GetPairContractCases 200 [success-200-required]",
                "GetPairContractCases 404 KIND_SWITCHED_OFF"));
    }
}
