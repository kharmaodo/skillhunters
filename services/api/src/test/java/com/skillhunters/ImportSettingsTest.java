package com.skillhunters;
import com.skillhunters.settings.ImportSettings;
import com.skillhunters.shared.ApiProblem.Rejected;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class ImportSettingsTest extends IdentityTestSupport {
    @Autowired ImportSettings settings;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    ImportSettings.Edit valid(){return new ImportSettings.Edit(new ImportSettings.Limits(1048576,10,10485760,100,104857600),"Réglage synthétique");}
    @Test void settingsRequireAdminCsrfAndCurrentVersionAndDoNotExposeSecrets() throws Exception {
        var root="/api/v1/admin/import-settings";
        mvc.perform(get(root).with(as("alice"))).andExpect(status().isForbidden());
        mvc.perform(get(root+"/template").with(as("alice"))).andExpect(status().isForbidden());
        mvc.perform(put(root).with(as("admin")).contentType("application/json").content(json.writeValueAsString(valid()))).andExpect(status().isForbidden());
        assertThatThrownBy(()->settings.publish(admin,null,valid())).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("VERSION_REQUIRED"));
        var v=settings.publish(admin,"\"1\"",valid());assertThat(v.version()).isEqualTo(2);assertThat(v.actorId()).isEqualTo(admin);
        assertThatThrownBy(()->settings.publish(admin,"\"1\"",valid())).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("SETTINGS_CHANGED"));
        var body=mvc.perform(get(root).with(as("admin"))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("password","secret","token","S3_","OIDC", "endpoint");
        mvc.perform(get("/api/v1/pools/"+poolA+"/import-settings").with(as("alice"))).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.actorId").doesNotExist()).andExpect(jsonPath("$.reason").doesNotExist());
        mvc.perform(get("/api/v1/pools/"+poolB+"/import-settings").with(as("alice"))).andExpect(status().isNotFound());
        db.update("UPDATE app_user SET enabled=FALSE WHERE id=?",admin);assertThatThrownBy(()->settings.get(admin)).isInstanceOf(Rejected.class);
    }
    @Test void securityCeilingsAndCrossFieldBoundsCannotBeRaised() {
        settings.get(admin);
        for(var l:java.util.List.of(new ImportSettings.Limits(15728641,100,314572800,1000,3145728000L),new ImportSettings.Limits(1,101,100,1,100),new ImportSettings.Limits(100,1,99,1,100),new ImportSettings.Limits(1,1,1,0,100),new ImportSettings.Limits(1,1,1,1,1099511627777L)))
            assertThatThrownBy(()->settings.publish(admin,"\"1\"",new ImportSettings.Edit(l,"Invalid"))).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("INVALID_SETTINGS"));
        assertThat(settings.get(admin).version()).isEqualTo(1);
    }
    @Test void historyPreservesValuesAndCleanTemplateHasMatchingHash() throws Exception {
        var initial=settings.get(admin);for(int i=1;i<=26;i++)settings.publish(admin,"\""+i+"\"",valid());
        var first=settings.history(admin,null);assertThat(first.items()).hasSize(25);assertThat(settings.history(admin,first.nextCursor()).items()).hasSize(2);assertThat(settings.version(1)).isEqualTo(initial);
        byte[] template=mvc.perform(get("/api/v1/admin/import-settings/template").with(as("admin"))).andExpect(status().isOk()).andExpect(header().string("Content-Disposition","attachment; filename=skillhunters-cv-template-v1.docx")).andReturn().getResponse().getContentAsByteArray();
        assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(template))).isEqualTo(initial.templateSha256());
        assertThat(initial.templateVersion()).isEqualTo("skillhunters-cv-template-v1");
    }
}
