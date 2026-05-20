// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.jacoco;

import jakarta.xml.bind.annotation.*;

/**
 * JAXB model for the JaCoCo XML {@code <counter>} element.
 *
 * <p>Represents a coverage counter with a type (INSTRUCTION, BRANCH, LINE,
 * COMPLEXITY, METHOD) and counts of missed and covered items. The ratio of
 * {@code covered / (missed + covered)} gives the coverage percentage.</p>
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class JacocoCounter {

    /** Counter type: INSTRUCTION, BRANCH, LINE, COMPLEXITY, or METHOD. */
    @XmlAttribute(name = "type")
    private String type;

    /** Number of items not covered (e.g. unexecuted instructions). */
    @XmlAttribute(name = "missed")
    private int missed;

    /** Number of items covered (e.g. executed instructions). */
    @XmlAttribute(name = "covered")
    private int covered;

    public String getType() {
        return type;
    }

    public int getMissed() {
        return missed;
    }

    public int getCovered() {
        return covered;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setMissed(int missed) {
        this.missed = missed;
    }

    public void setCovered(int covered) {
        this.covered = covered;
    }
}
