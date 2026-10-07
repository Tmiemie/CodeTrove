package com.codetrove.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class CodeTroveApplicationTests {

    @Autowired
    private DataSource dataSource;

    @Test
    void applicationContextLoadsWithMigratedDatabase() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                 "SELECT metadata_value FROM codetrove_platform_metadata WHERE metadata_key = ?")) {
            statement.setString(1, "schema_baseline");
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("M0");
            }
        }
    }
}
