package com.skillhunters;

import com.skillhunters.taxonomy.TaxonomyService;
import com.skillhunters.taxonomy.TaxonomyService.*;
import com.skillhunters.shared.ApiProblem.Rejected;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TaxonomyTest extends IdentityTestSupport {
    @Autowired TaxonomyService taxonomy;
    Edit edit(String name,String...aliases) {return new Edit(name,"Technology",List.of(aliases),"Vérification synthétique");}
    String tag() {return "\""+taxonomy.dictionary(null).version()+"\"";}
    @Test void exactNormalizationRetainsOriginalAndDoesNotInferRelatedTechnology() {
        var d=taxonomy.edit(admin,null,tag(),edit("PostgreSQL","Postgres"));
        d=taxonomy.edit(admin,null,tag(),edit("Java"));
        var match=TaxonomyService.normalize(d," Postgresql ");
        assertThat(match.original()).isEqualTo(" Postgresql ");assertThat(match.canonical()).isEqualTo("PostgreSQL");
        assertThat(match.dictionaryVersion()).isEqualTo(d.version());
        assertThat(TaxonomyService.normalize(d,"JavaScript").skillId()).isNull();
        assertThat(TaxonomyService.normalize(d,"Spring Boot").skillId()).isNull();
        assertThat(TaxonomyService.normalize(d,"Postgres").skillId()).isEqualTo(match.skillId());
    }
    @Test void collisionsAndStaleUpdatesLeaveVersionUntouched() {
        var d=taxonomy.edit(admin,null,tag(),edit("PostgreSQL","Postgres"));
        assertThatThrownBy(()->taxonomy.edit(admin,null,tag(),edit("Other"," POSTGRES "))).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("SKILL_ALIAS_COLLISION"));
        assertThat(taxonomy.dictionary(null).version()).isEqualTo(d.version());
        assertThatThrownBy(()->taxonomy.edit(admin,null,"\"0\"",edit("Java"))).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("DICTIONARY_CHANGED"));
    }
    @Test void confirmedMergePreservesOldDictionaryAndTransfersAliases() {
        var first=taxonomy.edit(admin,null,tag(),edit("PostgreSQL","Postgres"));
        var second=taxonomy.edit(admin,null,tag(),edit("Legacy PG","pg"));
        var target=first.skills().getFirst().id();var source=second.skills().stream().filter(s->s.name().equals("Legacy PG")).findFirst().orElseThrow().id();
        assertThatThrownBy(()->taxonomy.merge(admin,source,tag(),new Merge(target,false,"test"))).isInstanceOf(Rejected.class);
        var merged=taxonomy.merge(admin,source,tag(),new Merge(target,true,"Synonymes confirmés"));
        assertThat(TaxonomyService.normalize(merged,"pg").skillId()).isEqualTo(target);
        assertThat(TaxonomyService.normalize(taxonomy.dictionary(second.version()),"pg").skillId()).isEqualTo(source);
        assertThat(taxonomy.history(0).getFirst().action()).isEqualTo("MERGE");
    }
    @Test void writesRequireAdminCsrfAndVersion() throws Exception {
        String body="{\"name\":\"Java\",\"category\":\"Language\",\"aliases\":[],\"reason\":\"Test\"}";
        mvc.perform(post("/api/v1/taxonomy/skills").with(as("alice")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/taxonomy/skills").with(as("admin")).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/taxonomy/skills").with(as("admin")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isPreconditionRequired());
        mvc.perform(get("/api/v1/taxonomy").with(as("alice"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/taxonomy/history").with(as("alice"))).andExpect(status().isForbidden());
        assertThatThrownBy(()->taxonomy.edit(alice,null,tag(),edit("Java"))).isInstanceOf(Rejected.class);
    }
}
