package com.ledgerengine.repository;

import com.ledgerengine.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, java.util.UUID> {
}
