// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.jacoco;

import jakarta.xml.bind.annotation.*;
import java.util.List;

/**
 * JAXB model for the JaCoCo XML {@code <method>} element.
 *
 * <p>Represents a Java method in the coverage report, with its name, JVM descriptor,
 * source line number, and coverage counters (INSTRUCTION, BRANCH, LINE, etc.).
 * A method is considered "covered" if it has at least one covered INSTRUCTION counter.</p>
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class JacocoMethod {

    /** Method name (e.g. {@code getName}, {@code <init>} for constructors). */
    @XmlAttribute(name = "name")
    private String name;

    /** JVM method descriptor (e.g. {@code ()Ljava/lang/String;} for a no-arg method returning String). */
    @XmlAttribute(name = "desc")
    private String desc;

    /** Source line number where this method is defined. */
    @XmlAttribute(name = "line")
    private int line;

    /** Coverage counters for this method (INSTRUCTION, BRANCH, LINE, COMPLEXITY, METHOD). */
    @XmlElement(name = "counter")
    private List<JacocoCounter> counters;

    public String getName() {
        return name;
    }

    public List<JacocoCounter> getCounters() {
        return counters;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setCounters(List<JacocoCounter> counters) {
        this.counters = counters;
    }

    /**
     * Returns the total number of covered instructions for this method.
     * Used to determine if this method was actually executed during a test.
     *
     * @return sum of covered instruction counts, or 0 if no counters are present
     */
    public int getCoveredCount() {
        return counters == null ? 0 :
              counters.stream()
                    .filter(c -> "INSTRUCTION".equals(c.getType()))
                    .mapToInt(JacocoCounter::getCovered)
                    .sum();
    }


}
