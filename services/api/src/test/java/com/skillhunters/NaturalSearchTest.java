package com.skillhunters;

import com.skillhunters.search.*;
import com.skillhunters.taxonomy.TaxonomyService;
import com.skillhunters.talent.ReviewService.Kind;
import com.skillhunters.shared.ApiProblem.Rejected;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NaturalSearchTest extends IdentityTestSupport {
    @Autowired NaturalSearchService natural;
    @Autowired TaxonomyService taxonomy;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @BeforeEach void dictionary() {
        taxonomy.edit(admin,null,"\"0\"",new TaxonomyService.Edit("Java","Language",List.of("JDK"),"Synthetic"));
        taxonomy.edit(admin,null,"\"1\"",new TaxonomyService.Edit("Spring Boot","Framework",List.of(),"Synthetic"));
    }
    NaturalSearchService.Proposal propose() {return natural.propose(alice,new NaturalSearchService.Request(poolA,"cinq ans Java et Spring Boot"));}
    StructuredSearchService.Query query(NaturalSearchService.Proposal p) {return new StructuredSearchService.Query(poolA,p.interpretation().criteria(),false,List.of(Kind.EMPLOYMENT),null,p.interpretation().dictionaryVersion(),null,20);}
    @Test void interpretationDoesNotSearchAndOnlyConfirmedEditsAreTraced() {
        var p=propose();assertThat(db.queryForObject("SELECT COUNT(*) FROM identity_audit WHERE action='STRUCTURED_SEARCH'",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT interpretation_json FROM natural_search_proposal",String.class)).doesNotContain("cinq ans");
        var q=query(p);var edited=new StructuredSearchService.Query(poolA,List.of(new StructuredSearchService.Criterion("Java",StructuredSearchService.Importance.DESIRED,24,12)),true,q.experienceKinds(),null,q.dictionaryVersion(),null,20);
        assertThatThrownBy(()->natural.confirm(alice,p.id(),new NaturalSearchService.Confirmation(false,edited))).isInstanceOf(Rejected.class);
        var r=natural.confirm(alice,p.id(),new NaturalSearchService.Confirmation(true,edited));
        assertThat(r.trace().criteria().getFirst().minimumMonths()).isEqualTo(24);assertThat(r.trace().strict()).isTrue();
        assertThat(db.queryForObject("SELECT confirmed_trace_json FROM natural_search_proposal",String.class)).contains(r.trace().fingerprint());
        assertThatThrownBy(()->natural.confirm(alice,p.id(),new NaturalSearchService.Confirmation(true,edited))).isInstanceOf(Rejected.class);
    }
    @Test void actorScopeRevocationAndExpirationAreRechecked() {
        assertThatThrownBy(()->natural.propose(admin,new NaturalSearchService.Request(poolA,"Java"))).isInstanceOf(Rejected.class);
        assertThatThrownBy(()->natural.propose(alice,new NaturalSearchService.Request(poolB,"Java"))).isInstanceOf(Rejected.class);
        var p=propose();db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITER')",admin,poolA);
        assertThatThrownBy(()->natural.confirm(admin,p.id(),new NaturalSearchService.Confirmation(true,query(p)))).isInstanceOf(Rejected.class);
        db.update("DELETE FROM membership WHERE user_id=?",alice);
        assertThatThrownBy(()->natural.confirm(alice,p.id(),new NaturalSearchService.Confirmation(true,query(p)))).isInstanceOf(Rejected.class);
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITER')",alice,poolA);
        db.update("UPDATE natural_search_proposal SET expires_at=TIMESTAMP '2000-01-01 00:00:00'");
        assertThatThrownBy(()->natural.confirm(alice,p.id(),new NaturalSearchService.Confirmation(true,query(p)))).isInstanceOf(Rejected.class);
        natural.purge();assertThat(db.queryForObject("SELECT COUNT(*) FROM natural_search_proposal",Integer.class)).isZero();
    }
    @Test void dictionaryIsFrozenAndInvalidEditsCannotBypassWhitelist() {
        var p=propose();var q=query(p);
        taxonomy.edit(admin,null,"\"2\"",new TaxonomyService.Edit("Kafka","Messaging",List.of(),"Synthetic"));
        for(String skill:List.of("homme","Kafka","invented")) {
            var changed=new StructuredSearchService.Query(poolA,List.of(new StructuredSearchService.Criterion(skill,StructuredSearchService.Importance.REQUIRED,null,null)),false,q.experienceKinds(),null,q.dictionaryVersion(),null,20);
            assertThatThrownBy(()->natural.confirm(alice,p.id(),new NaturalSearchService.Confirmation(true,changed))).isInstanceOf(Rejected.class);
        }
        assertThat(natural.confirm(alice,p.id(),new NaturalSearchService.Confirmation(true,q)).trace().dictionaryVersion()).isEqualTo(2);
    }
    @Test void endpointsRequireCsrfAndExplicitConfirmationWithNoStore() throws Exception {
        String body=json.writeValueAsString(new NaturalSearchService.Request(poolA,"Java"));
        mvc.perform(post("/api/v1/search/natural/proposals").with(as("alice")).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/search/natural/proposals").with(as("alice")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        var p=propose();String confirmation=json.writeValueAsString(new NaturalSearchService.Confirmation(true,query(p)));
        String url="/api/v1/search/natural/proposals/"+p.id()+"/confirm";
        mvc.perform(post(url).with(as("alice")).contentType("application/json").content(confirmation)).andExpect(status().isForbidden());
        mvc.perform(post(url).with(as("alice")).with(csrf()).contentType("application/json").content(confirmation)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post("/api/v1/search/natural/proposals").with(as("alice")).with(csrf()).contentType("application/json").content(body.replace("\"text\"","\"age\""))).andExpect(status().isBadRequest());
    }
}
