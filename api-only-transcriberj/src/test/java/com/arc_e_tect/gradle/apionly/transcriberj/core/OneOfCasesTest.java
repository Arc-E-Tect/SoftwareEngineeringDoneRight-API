package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request body that is a {@code oneOf} of named branches: every branch is a way to call the
 * operation, so each gets a success case; a rule inside a branch gets a case when breaking it is
 * proven to make the body match no branch; and two branches merged into a body that matches none
 * give the case of the {@code oneOf} itself. Every case here is also checked by the independent
 * validator of {@link InvalidRequestOracleTest}, which the corpus contract is part of.
 */
@DisplayName("oneOf request bodies")
class OneOfCasesTest {

    private static final String CASES = "PostSignUpContractCases";

    @TempDir
    static Path directory;

    static ValidValueFixtures.Fixture fixture;

    @BeforeAll
    static void generate() {
        fixture = InvalidRequestFixtures.corpus("choices", directory);
    }

    private static List<JsonNode> cases(String kind) {
        return ContractCaseFixtures.cases(fixture.report(), kind).stream()
                .filter(c -> c.get("class").stringValue().equals(CASES)).toList();
    }

    private static List<String> ids(String kind) {
        return cases(kind).stream().map(c -> c.get("id").stringValue()).toList();
    }

    private static JsonNode caseWithId(String id) {
        return ContractCaseFixtures.cases(fixture.report()).stream()
                .filter(c -> c.get("class").stringValue().equals(CASES) && c.get("id").stringValue().equals(id))
                .findFirst().orElseThrow(() -> new AssertionError("no case " + id));
    }

    private static JsonNode body(String id) {
        return caseWithId(id).get("request").get("body");
    }

    private static List<JsonNode> coverage(String pointer, String keyword) {
        List<JsonNode> out = new ArrayList<>();
        fixture.report().get("constraintCoverage").forEach(e -> {
            if (e.get("pointer").stringValue().equals(pointer) && e.get("keyword").stringValue().equals(keyword)) {
                out.add(e);
            }
        });
        return out;
    }

    @Nested
    @DisplayName("Success")
    class Success {

        @Test
        @DisplayName("every branch gets a success case: the first as the required request, every other by its name")
        void everyBranchGetsASuccessCase() {
            assertThat(ids("SUCCESS")).containsExactly("success-201-required", "success-201-voucher-sign-up-v1",
                    "success-201-invite-sign-up-v1");
        }

        @Test
        @DisplayName("a branch's success case sends that branch's required members, and names the branch")
        void aBranchsSuccessCaseSendsThatBranchsRequiredMembers() {
            JsonNode voucher = caseWithId("success-201-voucher-sign-up-v1");
            assertThat(voucher.get("variant").stringValue()).isEqualTo("required");
            assertThat(voucher.get("branch").stringValue()).isEqualTo("VoucherSignUpV1");
            assertThat(body("success-201-voucher-sign-up-v1").propertyNames())
                    .containsExactlyInAnyOrder("handle", "email", "voucher");

            JsonNode invite = caseWithId("success-201-invite-sign-up-v1");
            assertThat(invite.get("branch").stringValue()).isEqualTo("InviteSignUpV1");
            assertThat(body("success-201-invite-sign-up-v1").propertyNames())
                    .containsExactlyInAnyOrder("handle", "email", "invite");
        }

        @Test
        @DisplayName("the required success case names no branch, as every case that is not about a branch")
        void theRequiredSuccessCaseNamesNoBranch() {
            assertThat(caseWithId("success-201-required").get("branch").isNull()).isTrue();
            assertThat(body("success-201-required").propertyNames()).containsExactlyInAnyOrder("handle", "email");
        }
    }

    @Nested
    @DisplayName("Rules inside a branch")
    class RulesInsideABranch {

        @Test
        @DisplayName("a rule every branch shares gets one case, and covers its place in every branch")
        void aRuleEveryBranchSharesGetsOneCase() {
            for (String[] rule : List.of(new String[]{"/handle", "pattern", "body-handle-pattern"},
                    new String[]{"/handle", "minLength", "body-handle-minLength"},
                    new String[]{"/handle", "maxLength", "body-handle-maxLength"},
                    new String[]{"/email", "maxLength", "body-email-maxLength"})) {
                assertThat(ids("INVALID_REQUEST")).as(rule[2]).containsOnlyOnce(rule[2]);
                List<JsonNode> entries = coverage(rule[0], rule[1]);
                assertThat(entries).as("%s %s", rule[1], rule[0]).isNotEmpty();
                entries.forEach(e -> assertThat(e.get("cases").get(0).stringValue()).as(e.toString())
                        .isEqualTo(rule[2]));
            }
        }

        @Test
        @DisplayName("a rule of one branch only is broken in a request of that branch")
        void aRuleOfOneBranchOnlyIsBrokenInARequestOfThatBranch() {
            JsonNode voucher = body("body-voucher-pattern");
            assertThat(voucher.propertyNames()).containsExactlyInAnyOrder("handle", "email", "voucher");
            assertThat(voucher.get("voucher").stringValue()).doesNotMatch("^V-[0-9]{4}$");

            JsonNode invite = body("body-invite-pattern");
            assertThat(invite.propertyNames()).containsExactlyInAnyOrder("handle", "email", "invite");
            assertThat(invite.get("invite").stringValue()).doesNotMatch("^I-[a-f0-9]{8}$");
        }

        @Test
        @DisplayName("an unknown member, which no branch allows, gets one case")
        void anUnknownMemberGetsOneCase() {
            assertThat(ids("INVALID_REQUEST")).containsOnlyOnce("body-unknown-member");
        }

        @Test
        @DisplayName("leaving out a branch's own required member gives a body of another branch, so no case, and says which")
        void leavingOutABranchsOwnRequiredMemberGivesNoCase() {
            assertThat(ids("INVALID_REQUEST")).doesNotContain("body-voucher-required", "body-invite-required");
            JsonNode entry = coverage("/voucher", "required").get(0);
            assertThat(entry.get("uncovered").get("code").stringValue()).isEqualTo("NOT_ISOLATABLE");
            assertThat(entry.get("uncovered").get("detail").stringValue())
                    .contains("still matches the branch at /components/schemas/PlainSignUpV1");
        }
    }

    @Nested
    @DisplayName("The oneOf itself")
    class TheOneOfItself {

        @Test
        @DisplayName("two branches merged into a body that matches no branch give the case of the oneOf, named after both")
        void twoBranchesMergedIntoABodyThatMatchesNoBranchGiveACase() {
            String id = "body-oneOf-voucher-sign-up-v1-invite-sign-up-v1";
            assertThat(ids("INVALID_REQUEST")).containsOnlyOnce(id);
            assertThat(caseWithId(id).get("keyword").stringValue()).isEqualTo("oneOf");
            assertThat(body(id).propertyNames()).containsExactlyInAnyOrder("handle", "email", "voucher", "invite");
        }

        @Test
        @DisplayName("merges that still match a branch give no case, and the oneOf's coverage names the case it has")
        void mergesThatStillMatchABranchGiveNoCase() {
            assertThat(ids("INVALID_REQUEST")).noneMatch(id -> id.startsWith("body-oneOf-plain-sign-up-v1"));
            JsonNode entry = coverage("", "oneOf").get(0);
            assertThat(entry.get("cases").get(0).stringValue())
                    .isEqualTo("body-oneOf-voucher-sign-up-v1-invite-sign-up-v1");
        }
    }
}
