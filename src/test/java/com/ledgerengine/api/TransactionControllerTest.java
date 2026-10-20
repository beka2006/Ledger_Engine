package com.ledgerengine.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * These tests use only the public API, the same way a real client would:
 * accounts are created over HTTP, money enters the system through a posting
 * from an overdraft-allowed "external funding" account, and every check is made
 * on an HTTP response. If any layer is wired wrongly, one of these fails.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- helpers ----------

    private String createAccount(String owner, boolean overdraftAllowed) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("ownerName", owner);
        body.put("currency", "EUR");
        body.put("overdraftAllowed", overdraftAllowed);

        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private ObjectNode entry(String accountId, String type, String amount) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("accountId", accountId);
        node.put("type", type);
        node.put("amount", new BigDecimal(amount));
        return node;
    }

    private String body(String description, ObjectNode... entries) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("description", description);
        ArrayNode array = root.putArray("entries");
        for (ObjectNode entry : entries) {
            array.add(entry);
        }
        return root.toString();
    }

    private ResultActions postTransaction(String key, String json) throws Exception {
        return mockMvc.perform(post("/api/transactions")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private static String newKey() {
        return "http-test-" + UUID.randomUUID();
    }

    /** Moves money from the outside world into an account, expecting success. */
    private void fund(String fundingId, String accountId, String amount) throws Exception {
        postTransaction(newKey(), body("Funding",
                entry(fundingId, "CREDIT", amount),
                entry(accountId, "DEBIT", amount)))
                .andExpect(status().isCreated());
    }

    private BigDecimal balanceOf(String accountId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/accounts/" + accountId + "/balance"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("balance").decimalValue();
    }

    // ---------- the happy path ----------

    @Test
    void moneyEntersTheSystemMovesBetweenAccountsAndTheLedgerStaysBalanced() throws Exception {
        String funding = createAccount("External funding", true);
        String alice = createAccount("Alice", false);
        String bob = createAccount("Bob", false);

        postTransaction(newKey(), body("Funding Alice",
                entry(funding, "CREDIT", "100.00"),
                entry(alice, "DEBIT", "100.00")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/transactions/")))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.description").value("Funding Alice"))
                .andExpect(jsonPath("$.entries", hasSize(2)));

        postTransaction(newKey(), body("Alice pays Bob",
                entry(alice, "CREDIT", "30.00"),
                entry(bob, "DEBIT", "30.00")))
                .andExpect(status().isCreated());

        assertThat(balanceOf(alice)).isEqualByComparingTo("70");
        assertThat(balanceOf(bob)).isEqualByComparingTo("30");
        assertThat(balanceOf(funding)).isEqualByComparingTo("-100");

        // Double-entry invariant, observed from outside: across all accounts the balances cancel out.
        assertThat(balanceOf(alice).add(balanceOf(bob)).add(balanceOf(funding))).isEqualByComparingTo("0");

        mockMvc.perform(get("/api/accounts/" + alice + "/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    // ---------- idempotency over HTTP ----------

    @Test
    void aRepeatedRequestIsReplayedNotPostedAgain() throws Exception {
        String funding = createAccount("External funding", true);
        String alice = createAccount("Alice", false);
        String key = newKey();
        String json = body("Funding", entry(funding, "CREDIT", "100.00"), entry(alice, "DEBIT", "100.00"));

        MvcResult first = postTransaction(key, json)
                .andExpect(status().isCreated())
                .andReturn();
        String firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();

        postTransaction(key, json)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(firstId))
                .andExpect(jsonPath("$.entries", hasSize(2)));

        // Posted once, not twice.
        assertThat(balanceOf(alice)).isEqualByComparingTo("100");
    }

    @Test
    void reusingAKeyForADifferentRequestReturns422() throws Exception {
        String funding = createAccount("External funding", true);
        String alice = createAccount("Alice", false);
        String key = newKey();

        postTransaction(key, body("Funding", entry(funding, "CREDIT", "100.00"), entry(alice, "DEBIT", "100.00")))
                .andExpect(status().isCreated());

        postTransaction(key, body("Funding", entry(funding, "CREDIT", "200.00"), entry(alice, "DEBIT", "200.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString(key)));

        assertThat(balanceOf(alice)).isEqualByComparingTo("100");
    }

    @Test
    void aFailedRequestDoesNotConsumeItsKey() throws Exception {
        String funding = createAccount("External funding", true);
        String alice = createAccount("Alice", false);
        String bob = createAccount("Bob", false);
        String key = newKey();

        // Alice has nothing, so this is refused...
        postTransaction(key, body("Too early", entry(alice, "CREDIT", "50.00"), entry(bob, "DEBIT", "50.00")))
                .andExpect(status().isUnprocessableEntity());

        // ...and because nothing was stored, the same key is still free to use.
        postTransaction(key, body("Funding", entry(funding, "CREDIT", "100.00"), entry(alice, "DEBIT", "100.00")))
                .andExpect(status().isCreated());

        assertThat(balanceOf(alice)).isEqualByComparingTo("100");
    }

    // ---------- business rules ----------

    @Test
    void anUnbalancedTransactionReturns422() throws Exception {
        String funding = createAccount("External funding", true);
        String alice = createAccount("Alice", false);

        postTransaction(newKey(), body("Off by ten",
                entry(funding, "CREDIT", "100.00"),
                entry(alice, "DEBIT", "90.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("not balanced")));
    }

    @Test
    void aTransferThatWouldOverdrawAnAccountReturns422() throws Exception {
        String alice = createAccount("Alice", false);
        String bob = createAccount("Bob", false);

        postTransaction(newKey(), body("No money",
                entry(alice, "CREDIT", "50.00"),
                entry(bob, "DEBIT", "50.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("insufficient funds")));

        assertThat(balanceOf(bob)).isEqualByComparingTo("0");
    }

    @Test
    void aTransactionReferencingAnUnknownAccountReturns404() throws Exception {
        String alice = createAccount("Alice", false);
        String unknown = UUID.randomUUID().toString();

        postTransaction(newKey(), body("Ghost",
                entry(unknown, "CREDIT", "10.00"),
                entry(alice, "DEBIT", "10.00")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString(unknown)));
    }

    // ---------- request validation (400) ----------

    @Test
    void aMissingIdempotencyKeyReturns400() throws Exception {
        String funding = createAccount("External funding", true);
        String alice = createAccount("Alice", false);

        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("No key", entry(funding, "CREDIT", "10.00"), entry(alice, "DEBIT", "10.00"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing required header: Idempotency-Key"));
    }

    @Test
    void anOverlongIdempotencyKeyReturns400() throws Exception {
        String funding = createAccount("External funding", true);
        String alice = createAccount("Alice", false);
        String tooLong = "k".repeat(256);

        postTransaction(tooLong, body("Long key", entry(funding, "CREDIT", "10.00"), entry(alice, "DEBIT", "10.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Idempotency-Key")));
    }

    @Test
    void fewerThanTwoEntriesIsRejectedWithAFieldError() throws Exception {
        String alice = createAccount("Alice", false);

        postTransaction(newKey(), body("Lonely entry", entry(alice, "DEBIT", "10.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.entries").exists());
    }

    @Test
    void aNonPositiveAmountIsRejectedWithAFieldError() throws Exception {
        String funding = createAccount("External funding", true);
        String alice = createAccount("Alice", false);

        postTransaction(newKey(), body("Negative",
                entry(funding, "CREDIT", "-5.00"),
                entry(alice, "DEBIT", "5.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['entries[0].amount']").exists());
    }

    @Test
    void anUnknownEntryTypeReturns400() throws Exception {
        String funding = createAccount("External funding", true);
        String alice = createAccount("Alice", false);

        postTransaction(newKey(), body("Sideways",
                entry(funding, "SIDEWAYS", "10.00"),
                entry(alice, "DEBIT", "10.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed or unreadable request body"));
    }

    // ---------- reading a transaction back ----------

    @Test
    void aPostedTransactionCanBeReadBackById() throws Exception {
        String funding = createAccount("External funding", true);
        String alice = createAccount("Alice", false);

        MvcResult created = postTransaction(newKey(), body("Funding Alice",
                entry(funding, "CREDIT", "100.00"),
                entry(alice, "DEBIT", "100.00")))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode createdBody = objectMapper.readTree(created.getResponse().getContentAsString());
        String id = createdBody.get("id").asText();

        // Follow the Location header exactly as a client would.
        String location = created.getResponse().getHeader("Location");
        assertThat(location).endsWith("/api/transactions/" + id);

        mockMvc.perform(get("/api/transactions/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.description").value("Funding Alice"))
                .andExpect(jsonPath("$.entries", hasSize(2)));
    }

    @Test
    void anUnknownTransactionReturns404() throws Exception {
        UUID unknown = UUID.randomUUID();

        mockMvc.perform(get("/api/transactions/" + unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString(unknown.toString())));
    }

    @Test
    void aMalformedTransactionIdReturns400() throws Exception {
        mockMvc.perform(get("/api/transactions/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
}
