package io.github.xxd763795151.esl.app;

import java.time.Duration;
import java.util.Objects;

/**
 * Arguments of the {@code play_and_get_digits} application.
 *
 * @param minDigits         minimum number of digits
 * @param maxDigits         maximum number of digits
 * @param tries             attempts before giving up
 * @param timeout           time to wait for the first digit after the prompt
 * @param terminators       digits that end input, e.g. {@code #}
 * @param prompt            sound file played as prompt
 * @param invalidPrompt     sound file played after invalid input, may be {@code null}
 * @param variable          channel variable receiving the digits
 * @param regex             input validation expression
 * @param digitTimeout      inter-digit timeout, {@code null} to use {@code timeout}
 * @param transferOnFailure extension to transfer to after all tries failed, may be {@code null}
 */
public record PlayAndGetDigits(int minDigits, int maxDigits, int tries, Duration timeout, String terminators,
                               String prompt, String invalidPrompt, String variable, String regex,
                               Duration digitTimeout, String transferOnFailure) {

    public static final String DEFAULT_VARIABLE = "esl_collected_digits";

    public PlayAndGetDigits {
        if (minDigits < 0 || maxDigits < minDigits || maxDigits < 1) {
            throw new IllegalArgumentException("require 0 <= minDigits <= maxDigits and maxDigits >= 1");
        }
        if (tries < 1) {
            throw new IllegalArgumentException("tries must be >= 1");
        }
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(prompt, "prompt");
        terminators = terminators == null || terminators.isEmpty() ? "none" : terminators;
        variable = variable == null ? DEFAULT_VARIABLE : variable;
        regex = regex == null ? "\\d+" : regex;
    }

    public static Builder builder(String prompt) {
        return new Builder(prompt);
    }

    /** Argument string in the positional order the application expects. */
    public String toArgument() {
        StringBuilder arg = new StringBuilder()
                .append(minDigits).append(' ')
                .append(maxDigits).append(' ')
                .append(tries).append(' ')
                .append(timeout.toMillis()).append(' ')
                .append(terminators).append(' ')
                .append(prompt).append(' ')
                .append(invalidPrompt == null ? "silence_stream://250" : invalidPrompt).append(' ')
                .append(variable).append(' ')
                .append(regex).append(' ')
                .append((digitTimeout == null ? timeout : digitTimeout).toMillis());
        if (transferOnFailure != null) {
            arg.append(' ').append(transferOnFailure);
        }
        return arg.toString();
    }

    public static final class Builder {
        private final String prompt;
        private int minDigits = 1;
        private int maxDigits = 1;
        private int tries = 3;
        private Duration timeout = Duration.ofSeconds(5);
        private String terminators = "#";
        private String invalidPrompt;
        private String variable;
        private String regex;
        private Duration digitTimeout;
        private String transferOnFailure;

        private Builder(String prompt) {
            this.prompt = prompt;
        }

        public Builder digits(int min, int max) {
            this.minDigits = min;
            this.maxDigits = max;
            return this;
        }

        public Builder tries(int tries) {
            this.tries = tries;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder terminators(String terminators) {
            this.terminators = terminators;
            return this;
        }

        public Builder invalidPrompt(String invalidPrompt) {
            this.invalidPrompt = invalidPrompt;
            return this;
        }

        public Builder variable(String variable) {
            this.variable = variable;
            return this;
        }

        public Builder regex(String regex) {
            this.regex = regex;
            return this;
        }

        public Builder digitTimeout(Duration digitTimeout) {
            this.digitTimeout = digitTimeout;
            return this;
        }

        public Builder transferOnFailure(String transferOnFailure) {
            this.transferOnFailure = transferOnFailure;
            return this;
        }

        public PlayAndGetDigits build() {
            return new PlayAndGetDigits(minDigits, maxDigits, tries, timeout, terminators, prompt, invalidPrompt,
                    variable, regex, digitTimeout, transferOnFailure);
        }
    }
}
