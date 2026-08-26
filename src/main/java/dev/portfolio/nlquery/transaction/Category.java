package dev.portfolio.nlquery.transaction;


import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "categories", uniqueConstraints = @UniqueConstraint(name = "uk_categories_name", columnNames = "name"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category {

    public Category(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("카테고리 이름은 필수입니다");
        this.name = name;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;


}
