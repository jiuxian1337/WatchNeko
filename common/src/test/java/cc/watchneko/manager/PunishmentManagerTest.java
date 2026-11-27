package cc.watchneko.manager;

import ac.grim.grimac.api.AbstractCheck;
import cc.watchneko.checks.CheckData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PunishmentManagerTest {

    @Test
    void broadGroupsDoNotEnableChecksThatRequireTheirFullName() {
        AbstractCheck existing = new NamedCheck("AutoclickerA", "UNKNOWN");
        AbstractCheck newCheck = new ExplicitCheck("AutoclickerC", "UNKNOWN");
        assertEquals(List.of(existing), PunishmentManager.selectChecks(List.of(existing, newCheck), List.of("Autoclicker")));
        assertEquals(List.of(), PunishmentManager.selectChecks(List.of(newCheck), List.of("UNKNOWN")));
    }

    @Test
    void fullNamesAndAliasesCanOptInWithNormalExclusions() {
        AbstractCheck check = new ExplicitCheck("AimX", "PreciseAim");
        List<AbstractCheck> checks = List.of(check);
        assertEquals(List.of(check), PunishmentManager.selectChecks(checks, List.of("aImX")));
        assertEquals(List.of(check), PunishmentManager.selectChecks(checks, List.of("preciseaim")));
        assertEquals(List.of(), PunishmentManager.selectChecks(checks, List.of("precise")));
        assertEquals(List.of(), PunishmentManager.selectChecks(checks, List.of("AimX", "!Aim")));
        assertEquals(List.of(), PunishmentManager.selectChecks(checks, List.of("!Aim", "AimX")));
    }

    private static class NamedCheck implements AbstractCheck {
        private final String name;
        private final String alias;

        NamedCheck(String name, String alias) {
            this.name = name;
            this.alias = alias;
        }

        @Override
        public String getCheckName() {
            return name;
        }

        @Override
        public String getAlternativeName() {
            return alias;
        }

        @Override
        public double getViolations() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getLastViolationTime() {
            throw new UnsupportedOperationException();
        }

        @Override
        public double getDecay() {
            throw new UnsupportedOperationException();
        }

        @Override
        public double getSetbackVL() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isExperimental() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getConfigName() {
            return name;
        }

        @Override
        public void reload() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isEnabled() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setEnabled(boolean enabled) {
            throw new UnsupportedOperationException();
        }
    }

    @CheckData(requiresExactPunishmentMatch = true)
    private static final class ExplicitCheck extends NamedCheck {
        ExplicitCheck(String name, String alias) {
            super(name, alias);
        }
    }
}
