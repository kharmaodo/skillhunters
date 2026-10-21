package com.skillhunters.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillhunters.shared.ApiProblem.Rejected;
import com.skillhunters.taxonomy.TaxonomyService;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NaturalSearchService {
    public record Request(UUID poolId,String text) {}
    public record Proposal(UUID id,UUID poolId,FrenchSearchInterpreter.Interpretation interpretation,OffsetDateTime expiresAt) {}
    public record Confirmation(boolean confirmed,StructuredSearchService.Query query) {}
    private final JdbcClient db;private final TransactionTemplate tx;private final ObjectMapper json;
    private final TaxonomyService taxonomy;private final StructuredSearchService search;
    public NaturalSearchService(JdbcClient db,TransactionTemplate tx,ObjectMapper json,TaxonomyService taxonomy,StructuredSearchService search) {
        this.db=db;this.tx=tx;this.json=json;this.taxonomy=taxonomy;this.search=search;
    }
    public Proposal propose(UUID actor,Request request) {
        if(request==null||request.poolId()==null) throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_NATURAL_SEARCH");
        return tx.execute(t->{
            authorize(actor,request.poolId());purge();
            if(db.sql("SELECT COUNT(*) FROM natural_search_proposal WHERE actor_id=:actor").param("actor",actor).query(Integer.class).single()>=50)
                throw new Rejected(HttpStatus.TOO_MANY_REQUESTS,"NATURAL_SEARCH_LIMIT");
            var interpretation=FrenchSearchInterpreter.interpret(request.text(),taxonomy.dictionary(null));
            var proposal=new Proposal(UUID.randomUUID(),request.poolId(),interpretation,OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30));
            db.sql("INSERT INTO natural_search_proposal(id,actor_id,pool_id,interpretation_json,expires_at) VALUES (:id,:actor,:pool,:data,:expires)")
                .param("id",proposal.id()).param("actor",actor).param("pool",request.poolId()).param("data",write(interpretation)).param("expires",proposal.expiresAt()).update();
            audit(actor,request.poolId(),"NATURAL_SEARCH_PROPOSED");return proposal;
        });
    }
    public StructuredSearchService.Result confirm(UUID actor,UUID id,Confirmation confirmation) {
        if(confirmation==null||!confirmation.confirmed()||confirmation.query()==null) throw new Rejected(HttpStatus.BAD_REQUEST,"SEARCH_CONFIRMATION_REQUIRED");
        var query=confirmation.query();
        return tx.execute(t->{
            authorize(actor,query.poolId());
            var stored=db.sql("SELECT interpretation_json FROM natural_search_proposal WHERE id=:id AND actor_id=:actor AND pool_id=:pool AND expires_at>:now AND confirmed_at IS NULL FOR UPDATE")
                .param("id",id).param("actor",actor).param("pool",query.poolId()).param("now",OffsetDateTime.now(ZoneOffset.UTC)).query(String.class).optional()
                .orElseThrow(()->new Rejected(HttpStatus.NOT_FOUND,"SEARCH_PROPOSAL_UNAVAILABLE"));
            FrenchSearchInterpreter.Interpretation interpretation;
            try {interpretation=json.readValue(stored,FrenchSearchInterpreter.Interpretation.class);}catch(Exception e){throw new IllegalStateException("NATURAL_SEARCH_STORAGE");}
            if(query.cursor()!=null||!Objects.equals(query.dictionaryVersion(),interpretation.dictionaryVersion())||query.criteria()==null)
                throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_NATURAL_SEARCH");
            var dictionary=taxonomy.dictionary(interpretation.dictionaryVersion());
            for(var c:query.criteria()) if(c==null||c.skill()==null||FrenchSearchInterpreter.forbidden(c.skill())||TaxonomyService.normalize(dictionary,c.skill()).skillId()==null)
                throw new Rejected(HttpStatus.BAD_REQUEST,"UNSUPPORTED_SEARCH_LANGUAGE");
            var result=search.search(actor,query);
            db.sql("UPDATE natural_search_proposal SET confirmed_at=:now,confirmed_trace_json=:trace,search_id=:search WHERE id=:id")
                .param("now",OffsetDateTime.now(ZoneOffset.UTC)).param("trace",write(result.trace())).param("search",result.searchId()).param("id",id).update();
            audit(actor,query.poolId(),"NATURAL_SEARCH_CONFIRMED");return result;
        });
    }
    private void authorize(UUID actor,UUID pool) {
        if(!db.sql("SELECT enabled FROM app_user WHERE id=:actor FOR UPDATE").param("actor",actor).query(Boolean.class).optional().orElse(false)) throw new Rejected(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED");
        if(pool==null||db.sql("SELECT COUNT(*) FROM membership WHERE user_id=:actor AND pool_id=:pool").param("actor",actor).param("pool",pool).query(Integer.class).single()==0) throw new Rejected(HttpStatus.NOT_FOUND,"POOL_NOT_FOUND");
    }
    private void audit(UUID actor,UUID pool,String action) {
        db.sql("INSERT INTO identity_audit(id,actor_id,action,pool_id) VALUES (:id,:actor,:action,:pool)").param("id",UUID.randomUUID()).param("actor",actor).param("action",action).param("pool",pool).update();
    }
    private String write(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("NATURAL_SEARCH_SERIALIZATION");}}
    @Scheduled(fixedDelay=60000)
    public void purge() {db.sql("DELETE FROM natural_search_proposal WHERE expires_at<=:now").param("now",OffsetDateTime.now(ZoneOffset.UTC)).update();}
}
