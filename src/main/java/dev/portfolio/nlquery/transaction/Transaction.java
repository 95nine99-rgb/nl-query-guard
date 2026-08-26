package dev.portfolio.nlquery.transaction;


import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "transactions")
public class Transaction {


    public Transaction(User user, Category category, String title, int amount, LocalDateTime transactionAt) {
        this.user = user;
        this.category = category;
        this.title = title;
        this.amount = amount;
        this.transactionAt = transactionAt;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false)
    private int amount;

    @Column(nullable = false, name = "transaction_at")
    private LocalDateTime transactionAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;
}
