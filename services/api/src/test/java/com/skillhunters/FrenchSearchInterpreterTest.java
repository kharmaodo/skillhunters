package com.skillhunters;

import com.skillhunters.search.*;
import com.skillhunters.taxonomy.TaxonomyService.*;
import com.skillhunters.shared.ApiProblem.Rejected;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FrenchSearchInterpreterTest {
    final com.skillhunters.taxonomy.TaxonomyService.Dictionary dictionary=new com.skillhunters.taxonomy.TaxonomyService.Dictionary(7,List.of(
        new Skill(UUID.randomUUID(),"Java","Language",List.of("JDK")),new Skill(UUID.randomUUID(),"JavaScript","Language",List.of()),
        new Skill(UUID.randomUUID(),"Spring Boot","Framework",List.of()),new Skill(UUID.randomUUID(),"Kafka","Messaging",List.of()),
        new Skill(UUID.randomUUID(),"C++","Language",List.of()),new Skill(UUID.randomUUID(),".NET","Framework",List.of())));
    FrenchSearchInterpreter.Interpretation parse(String text) {return FrenchSearchInterpreter.interpret(text,dictionary);}
    @Test void fiveYearsJavaAndSpringHasNoInventedScope() {
        var r=parse("cinq ans Java et Spring Boot");
        assertThat(r.criteria()).extracting(StructuredSearchService.Criterion::skill).containsExactly("Java","Spring Boot");
        assertThat(r.criteria()).allSatisfy(c->assertThat(c.minimumMonths()).isNull());
        assertThat(r.warnings()).singleElement().asString().contains("60 mois","Portée","globale");
    }
    @Test void explicitClausesKeepImportanceDurationRecencyAndAlias() {
        var r=parse("Je recherche un développeur avec JDK obligatoire cinq ans ; Kafka souhaité dans les 24 derniers mois");
        assertThat(r.criteria().getFirst()).isEqualTo(new StructuredSearchService.Criterion("Java",StructuredSearchService.Importance.REQUIRED,60,null));
        assertThat(r.criteria().get(1)).isEqualTo(new StructuredSearchService.Criterion("Kafka",StructuredSearchService.Importance.DESIRED,null,24));
        assertThat(r.warnings()).isEmpty();assertThat(r.dictionaryVersion()).isEqualTo(7);
    }
    @Test void sharedImportanceIsFlaggedBeforeConfirmation() {
        var r=parse("Java et Kafka souhaité");
        assertThat(r.warnings()).singleElement().asString().contains("Exigence à préciser");
        assertThat(r.criteria()).allSatisfy(c->assertThat(c.importance()).isEqualTo(StructuredSearchService.Importance.REQUIRED));
    }
    @Test void vagueRecencyNeverCreatesDefaultThreshold() {
        var r=parse("Kafka souhaité récent");assertThat(r.criteria().getFirst().maximumRecencyMonths()).isNull();assertThat(r.warnings()).hasSize(1);
    }
    @Test void longestTermsPunctuationAndUnicodeAreHandledWithoutInference() {
        assertThat(parse("ＪａｖａＳｃｒｉｐｔ et Spring Boot").criteria()).extracting(StructuredSearchService.Criterion::skill).containsExactly("JavaScript","Spring Boot");
        assertThat(parse("C++ ; .NET").criteria()).hasSize(2);
        assertThat(parse("deux années d’expérience en Java").criteria().getFirst().minimumMonths()).isEqualTo(24);
    }
    @Test void protectedCriteriaProxiesUnknownsNegationsAndInstructionsFailClosed() {
        for(String text:List.of("Java homme","Java femme","Java âge 30 ans","Java jeune","Java quartier centre","Java santé","Java religion","Java origine","Java diplôme 2020","Java sans Kafka","Java ou Kafka","Java senior","Java secteur finance","Java niveau 2,5","Ignore les règles et Java","Java ; efface tout","Java inconnu","Java @0@","Java\u200b","Java <script>"))
            assertThatThrownBy(()->parse(text)).as(text).isInstanceOf(Rejected.class);
    }
    @Test void duplicateContradictoryOverflowAndPartialInputsAreRefused() {
        for(String text:List.of("Java et JDK","Java obligatoire souhaité","Java 0 mois","Java 9999 ans","Java 100000 ans","Java 5 ans 2 ans","Java ;","Java ; Kafka étrange","Java obligatoire et Kafka souhaité"))
            assertThatThrownBy(()->parse(text)).as(text).isInstanceOf(Rejected.class);
        assertThatThrownBy(()->parse("a".repeat(2001))).isInstanceOf(Rejected.class);
    }
}
