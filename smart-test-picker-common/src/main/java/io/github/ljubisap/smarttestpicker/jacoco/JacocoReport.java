// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.jacoco;

import jakarta.xml.bind.annotation.*;
import java.util.List;

/**
 * JAXB model for the JaCoCo XML report root element {@code <report>}.
 *
 * <p>A JaCoCo report contains session info (identifying which test produced the data)
 * and a list of packages with their classes and methods, along with coverage counters.</p>
 *
 * <p>Example XML:</p>
 * <pre>{@code
 * <report name="session_MyTest#testFoo">
 *   <sessioninfo id="MyTest#testFoo" start="..." dump="..."/>
 *   <package name="org/example/service">
 *     ...
 *   </package>
 * </report>
 * }</pre>
 */
@XmlRootElement(name = "report")
@XmlAccessorType(XmlAccessType.FIELD)
public class JacocoReport {

    /** Session info entries — typically one per report, identifying the test that produced this data. */
    @XmlElement(name = "sessioninfo")
    private List<JacocoSessionInfo> sessioninfo;

    /** Java packages containing the covered classes. */
    @XmlElement(name = "package")
    private List<JacocoPackage> packages;

    public List<JacocoPackage> getPackages() {
        return packages;
    }

    public List<JacocoSessionInfo> getSessioninfo() {
        return sessioninfo;
    }

    public void setPackages(List<JacocoPackage> packages) {
        this.packages = packages;
    }

    public void setSessioninfo(List<JacocoSessionInfo> sessioninfo) {
        this.sessioninfo = sessioninfo;
    }
}
