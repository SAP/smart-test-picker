package com.sap.oss.smarttestpicker.maven;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import com.sap.oss.smarttestpicker.coverage.*;
import com.sap.oss.smarttestpicker.coverage.model.*;
import com.sap.oss.smarttestpicker.coverage.serialization.*;
import com.sap.oss.smarttestpicker.coverage.validation.ExecutableFragmentAssignmentValidator;

/** Calls production validators/joiner on retained bytes; never repairs evidence or publishes a map. */
public final class Check {
    public static void main(String[] args) throws Exception {
        Path subject=Path.of(args[0]),out=Path.of(args[1]);
        var assignment=new ExecutableShardAssignmentCodec().deserialize(Files.readAllBytes(subject.resolve(".stp/assignment.json")));
        var result=new TreeMap<String,Object>();
        Path path=subject.resolve("target/stp/coverage-fragment-v3.json");
        result.put("fragmentExists",Files.exists(path));
        if(Files.exists(path)) {
            var fragment=new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(path));
            result.put("completed",fragment.collectionCompleted());
            result.put("mapped",fragment.tests().keySet().stream().map(Object::toString).toList());
            result.put("unmapped",fragment.unmapped());
            result.put("setupScopes",fragment.setupScopes());
            try {ExecutableFragmentAssignmentValidator.validate(fragment,assignment,Set.of());result.put("assignmentValidation","ACCEPTED");}
            catch(Exception failure){result.put("assignmentValidation",failure.getClass().getSimpleName()+": "+failure.getMessage());}
            try {
                var map=new ExecutableCoverageFragmentJoiner().join(ExecutableTestInventory.from(assignment.revision(),assignment.tests()),
                    new ExecutableShardPlan(assignment.revision(),Map.of(assignment.shardId(),assignment.tests())),List.of(fragment),Set.of(),
                    Instant.EPOCH,new GeneratorProvenance("0.3.0-SNAPSHOT","ASM12","project",System.getProperty("java.version")));
                result.put("join","ACCEPTED_CANDIDATE");
                Files.write(out.resolve("candidate-map.json"),new ExecutableCoverageMapCodec().serialize(map));
            } catch(Exception failure){result.put("join",failure.getClass().getSimpleName()+": "+failure.getMessage());}
        }
        result.put("publication","NOT RUN: no Jenkins/storage operation; local candidate join only");
        Measure.write(out.resolve("validation.json"),result);
        System.out.println(Measure.JSON.toJson(result));
    }
}
