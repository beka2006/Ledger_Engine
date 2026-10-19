package com.ledgerengine.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerengine.model.Account;
import com.ledgerengine.model.EntryType;
import com.ledgerengine.model.LedgerEntry;
import com.ledgerengine.model.Transaction;
import com.ledgerengine.repository.AccountRepository;
import com.ledgerengine.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
 * Sends real HTTP-shaped requests through the whole stack (JSON parsing,
 * validation, controller, services, JPA, in-memory database, exception
 * handler, JSON response). Nothing is mocked, so these tests fail if any layer
 * is wired incorrectly, which the unit tests alone cannot show.
 * <p>
 * Decimal amounts are compared by parsing the JSON body rather than with
 * jsonPath().value(...), because 100, 100.0 and 100.0000 are the same money
 * but different JSON numbers, and a type-sensitive comparison would fail on
 * formatting rather than on correctness.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private JsonNode createAccountViaApi(String json) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void createsAnAccountAndReturnsItWithALocationHeader() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"Alice\",\"currency\":\"EUR\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/accounts/")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.ownerName").value("Alice"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.overdraftAllowed").value(false))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void honoursTheOverdraftFlagWhenRequested() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"External funding\",\"currency\":\"EUR\",\"overdraftAllowed\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.overdraftAllowed").value(true));
    }

    @Test
    void rejectsInvalidInputWithPerFieldErrors() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"   \",\"currency\":\"eur\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.ownerName").exists())
                .andExpect(jsonPath("$.fieldErrors.currency").exists());
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{this is not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed or unreadable request body"));
    }

    @Test
    void returnsAnExistingAccount() throws Exception {
        JsonNode created = createAccountViaApi("{\"ownerName\":\"Bob\",\"currency\":\"USD\"}");
        String id = created.get("id").asText();

        mockMvc.perform(get("/api/accounts/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.ownerName").value("Bob"))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void returns404ForAnUnknownAccount() throws Exception {
        UUID unknown = UUID.randomUUID();

        mockMvc.perform(get("/api/accounts/" + unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value(containsString(unknown.toString())));
    }

    @Test
    void returns400WhenTheAccountIdIsNotAUuid() throws Exception {
        mockMvc.perform(get("/api/accounts/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("id")));
    }

    @Test
    void aFreshAccountHasAZeroBalance() throws Exception {
        String id = createAccountViaApi("{\"ownerName\":\"Carol\",\"currency\":\"EUR\"}").get("id").asText();

        MvcResult result = mockMvc.perform(get("/api/accounts/" + id + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andReturn();

        BigDecimal balance = objectMapper.readTree(result.getResponse().getContentAsString()).get("balance").decimalValue();
        assertThat(balance).isEqualByComparingTo("0");
    }

    @Test
    void balanceAndEntriesReflectWhatWasPosted() throws Exception {
        // The transactions endpoint doesn't exist yet, so funding goes straight
        // through the repositories: debit the account 100, credit the outside world 100.
        Account account = accountRepository.save(new Account("Dave", "EUR"));
        Account outsideWorld = accountRepository.save(new Account("External funding", "EUR", true));

        Transaction funding = new Transaction("Initial funding");
        funding.addEntry(new LedgerEntry(accountRepository.getReferenceById(account.getId()), EntryType.DEBIT, new BigDecimal("100.00")));
        funding.addEntry(new LedgerEntry(accountRepository.getReferenceById(outsideWorld.getId()), EntryType.CREDIT, new BigDecimal("100.00")));
        transactionRepository.save(funding);

        MvcResult balanceResult = mockMvc.perform(get("/api/accounts/" + account.getId() + "/balance"))
                .andExpect(status().isOk())
                .andReturn();
        BigDecimal balance = objectMapper.readTree(balanceResult.getResponse().getContentAsString()).get("balance").decimalValue();
        assertThat(balance).isEqualByComparingTo("100");

        MvcResult entriesResult = mockMvc.perform(get("/api/accounts/" + account.getId() + "/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].type").value("DEBIT"))
                .andExpect(jsonPath("$[0].description").value("Initial funding"))
                .andReturn();
        BigDecimal amount = objectMapper.readTree(entriesResult.getResponse().getContentAsString()).get(0).get("amount").decimalValue();
        assertThat(amount).isEqualByComparingTo("100");
    }

    @Test
    void entriesOfAnUnknownAccountReturn404() throws Exception {
        mockMvc.perform(get("/api/accounts/" + UUID.randomUUID() + "/entries"))
                .andExpect(status().isNotFound());
    }
}
