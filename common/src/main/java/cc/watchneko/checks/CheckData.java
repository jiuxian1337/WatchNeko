package cc.watchneko.checks;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CheckData {
    String name() default "UNKNOWN";

    String alternativeName() default "UNKNOWN";

    String configName() default "DEFAULT";

    String description() default "No description provided";

    double decay() default 0.05;

    double setback() default 25;

    boolean experimental() default false;

    /** Whether an experimental check is gated by the global experimental-checks feature. */
    boolean requiresExperimentalChecks() default true;

    /** Whether a punishment group must list the full check name or a declared alias to include this check. */
    boolean requiresExactPunishmentMatch() default false;

}
