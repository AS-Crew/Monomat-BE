package io.github.ascrew.monomatbe.domain.map.repository;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest(properties = {
        "spring.test.database.replace=none",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class QuizMapSpecificationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("monomat_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @Autowired
    private QuizMapJpaRepository quizMapJpaRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void myMapSearch_appliesOwnerDeletedKeywordAndCategoryWithAndCondition() {
        User owner = userRepository.save(User.builder()
                .username("spec-owner")
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build());

        User anotherOwner = userRepository.save(User.builder()
                .username("spec-another-owner")
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build());

        QuizMap matched = quizMapJpaRepository.save(QuizMap.builder()
                .owner(owner)
                .title("OST 모음")
                .description("matched")
                .category(MapCategory.OST)
                .isPublic(false)
                .pendingPublic(true)
                .build());

        quizMapJpaRepository.save(QuizMap.builder()
                .owner(owner)
                .title("JPOP 모음")
                .description("category mismatch")
                .category(MapCategory.JPOP)
                .isPublic(false)
                .pendingPublic(false)
                .build());

        quizMapJpaRepository.save(QuizMap.builder()
                .owner(anotherOwner)
                .title("OST 모음")
                .description("another owner")
                .category(MapCategory.OST)
                .isPublic(false)
                .pendingPublic(false)
                .build());

        QuizMap deleted = quizMapJpaRepository.save(QuizMap.builder()
                .owner(owner)
                .title("OST 삭제 맵")
                .description("deleted")
                .category(MapCategory.OST)
                .isPublic(false)
                .pendingPublic(false)
                .build());
        deleted.softDelete();
        quizMapJpaRepository.flush();

        Specification<QuizMap> spec = Specification
                .where(QuizMapSpecification.ownedByAndNotDeleted(owner.getId()))
                .and(QuizMapSpecification.withKeywordIncludingCategory("ost"))
                .and(QuizMapSpecification.withCategory(MapCategory.OST));

        List<QuizMap> result = quizMapJpaRepository.findAll(spec);

        assertThat(result)
                .extracting(QuizMap::getId)
                .containsExactly(matched.getId());
    }

    @Test
    void myMapSearch_keywordCanMatchCategoryDisplayValue() {
        User owner = userRepository.save(User.builder()
                .username("spec-owner-anime")
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build());

        QuizMap animeMap = quizMapJpaRepository.save(QuizMap.builder()
                .owner(owner)
                .title("노래 모음")
                .description("keyword category match")
                .category(MapCategory.ANIME)
                .isPublic(false)
                .pendingPublic(false)
                .build());

        Specification<QuizMap> spec = Specification
                .where(QuizMapSpecification.ownedByAndNotDeleted(owner.getId()))
                .and(QuizMapSpecification.withKeywordIncludingCategory("애니"));

        List<QuizMap> result = quizMapJpaRepository.findAll(spec);

        assertThat(result)
                .extracting(QuizMap::getId)
                .containsExactly(animeMap.getId());
    }

    @Test
    void keywordSearch_treatsLikeWildcardCharactersAsPlainText() {
        User owner = userRepository.save(User.builder()
                .username("spec-owner-wildcard")
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build());

        QuizMap literalPercentMap = quizMapJpaRepository.save(QuizMap.builder()
                .owner(owner)
                .title("100% OST")
                .description("literal percent")
                .category(MapCategory.OST)
                .isPublic(false)
                .pendingPublic(false)
                .build());

        quizMapJpaRepository.save(QuizMap.builder()
                .owner(owner)
                .title("100점 OST")
                .description("should not match percent wildcard")
                .category(MapCategory.OST)
                .isPublic(false)
                .pendingPublic(false)
                .build());

        Specification<QuizMap> spec = Specification
                .where(QuizMapSpecification.ownedByAndNotDeleted(owner.getId()))
                .and(QuizMapSpecification.withKeywordIncludingCategory("100%"));

        List<QuizMap> result = quizMapJpaRepository.findAll(spec);

        assertThat(result)
                .extracting(QuizMap::getId)
                .containsExactly(literalPercentMap.getId());
    }
}