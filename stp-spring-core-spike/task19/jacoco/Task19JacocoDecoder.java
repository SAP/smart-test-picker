// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package task19.jacoco;

import java.io.File;
import java.io.PrintWriter;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.tools.ExecFileLoader;

public final class Task19JacocoDecoder {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("exec output class-dir...");
        ExecFileLoader loader = new ExecFileLoader();
        loader.load(new File(args[0]));
        CoverageBuilder coverage = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), coverage);
        for (int index = 2; index < args.length; index++) analyzer.analyzeAll(new File(args[index]));
        try (PrintWriter out = new PrintWriter(args[1])) {
            for (IClassCoverage type : coverage.getClasses()) {
                for (IMethodCoverage method : type.getMethods()) {
                    if (method.getInstructionCounter().getStatus() != ICounter.NOT_COVERED) {
                        out.println(type.getName().replace('/', '.') + "#" + method.getName() + method.getDesc());
                    }
                }
            }
        }
    }
}
