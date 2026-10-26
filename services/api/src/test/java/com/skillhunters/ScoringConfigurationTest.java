package com.skillhunters;

import com.skillhunters.scoring.*;
import com.skillhunters.scoring.ScoringConfigurations.*;
import com.skillhunters.shared.ApiProblem.Rejected;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ScoringConfigurationTest extends IdentityTestSupport {
    @Autowired ScoringConfigurations configs;
    String route(){return "/api/v1/pools/"+poolA+"/scoring-configurations";}
    void lead(){db.update("UPDATE membership SET role='RECRUITMENT_LEAD' WHERE user_id=? AND pool_id=?",alice,poolA);}
    Edit edit(int r,int d,int e,int recent){return new Edit("Poste backend",new Weights(r,d,e,recent),"Priorités du poste vérifiées");}
    @Test void versionsKeepBeforeAfterValidatorReasonAndNormalizedWeights() {
        lead();var initial=configs.state(alice,poolA);assertThat(initial.canConfigure()).isTrue();assertThat(initial.latest().version()).isZero();
        var one=configs.save(alice,poolA,"\"0\"",edit(1,3,0,0));var two=configs.save(alice,poolA,"\"1\"",edit(8,0,2,0));
        assertThat(one.normalized().get("REQUIRED")).isEqualTo(0.25);assertThat(one.normalized().values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1,within(1e-12));
        assertThat(two.before()).isEqualTo(one.weights());assertThat(two.validatorId()).isEqualTo(alice);assertThat(two.validatedAt()).isNotNull();assertThat(two.reason()).isEqualTo("Priorités du poste vérifiées");
        assertThat(configs.revision(alice,poolA,1L)).isEqualTo(one);assertThat(configs.history(alice,poolA,0).items()).containsExactly(two,one);
        assertThatThrownBy(()->configs.save(alice,poolA,"\"1\"",edit(2,2,2,2))).isInstanceOfSatisfying(Rejected.class,x->assertThat(x.code).isEqualTo("SCORING_CONFIGURATION_CHANGED"));
    }
    @Test void writesRequireLeadInThatPoolAndCsrfAndRejectMalformedWeights() throws Exception {
        String body="{\"name\":\"Backend\",\"weights\":{\"required\":35,\"desired\":15,\"experience\":20,\"recency\":10},\"reason\":\"Test\"}";
        mvc.perform(post(route()).with(as("alice")).with(csrf()).header("If-Match","\"0\"").contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(get(route()).with(as("admin"))).andExpect(status().isNotFound());
        lead();mvc.perform(post(route()).with(as("alice")).header("If-Match","\"0\"").contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(route()).with(as("alice")).with(csrf()).contentType("application/json").content(body)).andExpect(status().is(428));
        mvc.perform(post(route()).with(as("alice")).with(csrf()).header("If-Match","\"0\"").contentType("application/json").content(body.replace(":35",":0.5"))).andExpect(status().isBadRequest());
        for(Edit e:List.of(edit(0,0,0,0),edit(-1,2,3,4),edit(10001,0,0,0),new Edit("",new Weights(1,0,0,0),"Test"),new Edit("Poste",new Weights(1,0,0,0)," ")))
            assertThatThrownBy(()->configs.save(alice,poolA,"\"0\"",e)).isInstanceOf(Rejected.class);
        mvc.perform(post(route()).with(as("alice")).with(csrf()).header("If-Match","\"0\"").contentType("application/json").content(body)).andExpect(status().isCreated()).andExpect(header().string("ETag","\"1\""));
        assertThatThrownBy(()->configs.state(alice,poolB)).isInstanceOf(Rejected.class);
        db.update("UPDATE membership SET role='RECRUITER' WHERE user_id=?",alice);
        assertThat(configs.state(alice,poolA).canConfigure()).isFalse();assertThat(configs.revision(alice,poolA,1L).version()).isEqualTo(1);
        assertThatThrownBy(()->configs.save(alice,poolA,"\"1\"",edit(1,2,3,4))).isInstanceOfSatisfying(Rejected.class,x->assertThat(x.code).isEqualTo("SCORING_CONFIGURE_REQUIRED"));
    }
    @Test void competingValidatorsCannotOverwriteARevision() throws Exception {
        lead();var gate=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var results=new ArrayList<Future<String>>();
            for(int n=0;n<2;n++)results.add(executor.submit(()->{gate.await();try{configs.save(alice,poolA,"\"0\"",edit(1,2,3,4));return "OK";}catch(Rejected e){return e.code;}}));
            gate.countDown();var actual=new ArrayList<String>();for(var r:results)actual.add(r.get(10,TimeUnit.SECONDS));
            assertThat(actual).containsExactlyInAnyOrder("OK","SCORING_CONFIGURATION_CHANGED");assertThat(configs.history(alice,poolA,0).items()).hasSize(1);
        }
    }
}
