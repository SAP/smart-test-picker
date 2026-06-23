// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.jacoco;

import jakarta.xml.bind.annotation.*;
import java.util.List;

/**
 * JAXB model for the JaCoCo XML {@code <class>} element.
 *
 * <p>Represents a Java class in the coverage report, containing its methods
 * and their coverage counters. The class name uses internal format with slashes
 * (e.g. {@code org/example/service/UserService}).</p>
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class JacocoClass {

    /** Fully qualified class name in internal format (e.g. {@code org/example/Foo}). */
    @XmlAttribute(name = "name")
    private String name;

    /** Methods within this class that have coverage data. */
    @XmlElement(name = "method")
    private List<JacocoMethod> methods;

    /** Class-level coverage counters (LINE, BRANCH, INSTRUCTION, etc.). */
    @XmlElement(name = "counter")
    private List<JacocoCounter> counters;

    public String getName() {
        return name;
    }

    public List<JacocoMethod> getMethods() {
        return methods;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setMethods(List<JacocoMethod> methods) {
        this.methods = methods;
    }

    public List<JacocoCounter> getCounters() {
        return counters;
    }

    public void setCounters(List<JacocoCounter> counters) {
        this.counters = counters;
    }
}
