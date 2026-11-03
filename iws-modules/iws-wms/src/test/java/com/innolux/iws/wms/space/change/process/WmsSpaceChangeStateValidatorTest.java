package com.innolux.iws.wms.space.change.process;

import com.innolux.iws.process.enums.PostingMode;
import com.innolux.iws.process.runtime.StateContext;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WmsSpaceChangeStateValidatorTest {

    @Test
    void retirePlanAllowsCheckedTransitionAndRejectsChangeTransition() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any()))
            .thenReturn(List.of("SPACE_RETIRE"));
        WmsSpaceChangeStateValidator validator = new WmsSpaceChangeStateValidator(jdbc);

        StateContext checked = context("START", "CHECKED");
        StateContext wrong = context("START", "VALIDATED");

        assertThat(validator.validateStructural(checked).passed()).isTrue();
        assertThat(validator.validateStructural(wrong).passed()).isFalse();
    }

    @Test
    void changePlanAllowsValidatedButNotChecked() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any()))
            .thenReturn(List.of("SPACE_CHANGE"));
        WmsSpaceChangeStateValidator validator = new WmsSpaceChangeStateValidator(jdbc);

        assertThat(validator.validateStructural(context("START", "VALIDATED")).passed()).isTrue();
        assertThat(validator.validateStructural(context("START", "CHECKED")).passed()).isFalse();
    }

    private static StateContext context(String source, String target) {
        return new StateContext(
            10L, null, 20L, source, target, PostingMode.AUTOMATIC,
            "a".repeat(64), "SUCCEEDED", java.util.Map.of()
        );
    }
}
