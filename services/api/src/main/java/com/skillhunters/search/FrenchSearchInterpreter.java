package com.skillhunters.search;

import com.skillhunters.shared.ApiProblem.Rejected;
import com.skillhunters.taxonomy.TaxonomyService;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** Bounded grammar: unconsumed language is refused, never silently discarded. */
public final class FrenchSearchInterpreter {
    public static final String RULE="fr-explicit-grammar-v1";
    public record Interpretation(List<StructuredSearchService.Criterion> criteria,List<String> warnings,long dictionaryVersion,String rule) {}
    private record Term(String text,String canonical) {}
    private static final String NUMBER="(?:[0-9]{1,4}|un|une|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze)";
    private static final String UNIT="(?:ans?|annees?|mois)";
    private static final Pattern DURATION=Pattern.compile("(?<![\\p{L}\\p{N}])(?:au moins )?("+NUMBER+") ("+UNIT+")(?: d'experience)?(?: en| de| avec)?(?![\\p{L}\\p{N}])");
    private static final Pattern RECENCY=Pattern.compile("(?:utilisee?s? )?(?:dans|au cours de) (?:les )?("+NUMBER+") derniers? ("+UNIT+")");
    private static final Pattern SENSITIVE=Pattern.compile("(?<![\\p{L}\\p{N}])(?:age|ages|jeunes?|hommes?|femmes?|genre|sexe|origine|nationalite|religion|sante|handicap|enceinte|mariee?s?|celibataires?|enfants?|photo|loisirs?|associati\\p{L}*|ethni\\p{L}*|racial\\p{L}*|quartier|adresse|nom|prenom|diplome|naissance|retraite)(?![\\p{L}\\p{N}])");
    private FrenchSearchInterpreter() {}
    public static String fold(String value) {
        return Normalizer.normalize(Normalizer.normalize(value,Normalizer.Form.NFKC),Normalizer.Form.NFD)
            .replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replace('’','\'').replaceAll("\\s+"," ").strip();
    }
    public static boolean forbidden(String value) {return SENSITIVE.matcher(fold(value)).find();}
    public static Interpretation interpret(String text,TaxonomyService.Dictionary dictionary) {
        if(text==null||text.isBlank()||text.length()>2000||text.contains("@")||text.codePoints().anyMatch(c->Character.getType(c)==Character.FORMAT||Character.isISOControl(c)&&c!='\n')) reject("INVALID_NATURAL_SEARCH");
        String input=fold(text.replace('\n',';'));
        if(forbidden(input)) reject("PROHIBITED_SEARCH_CRITERION");
        input=input.replaceFirst("^(?:je recherche|je cherche|nous recherchons) (?:un developpeur (?:backend )?(?:avec )?)?","");
        var terms=new ArrayList<Term>();
        for(var skill:dictionary.skills()) {
            if(forbidden(skill.name())) continue;
            terms.add(new Term(fold(skill.name()),skill.name()));
            for(String alias:skill.aliases()) if(!forbidden(alias)) terms.add(new Term(fold(alias),skill.name()));
        }
        terms.sort(Comparator.comparingInt((Term t)->t.text().length()).reversed());
        var criteria=new ArrayList<StructuredSearchService.Criterion>();var warnings=new ArrayList<String>();var seen=new HashSet<String>();
        for(String raw:input.split(";",-1)) {
            String clause=raw.strip();if(clause.isEmpty()) reject("UNSUPPORTED_SEARCH_LANGUAGE");
            var names=new ArrayList<String>();
            for(var term:terms) {
                var matcher=Pattern.compile("(?<![\\p{L}\\p{N}_@])"+Pattern.quote(term.text())+"(?![\\p{L}\\p{N}_@])").matcher(clause);
                if(matcher.find()) {
                    int index=names.size();names.add(term.canonical());
                    clause=matcher.replaceAll(java.util.regex.Matcher.quoteReplacement("@"+index+"@"));
                }
            }
            Integer recent=null,months=null;
            var recency=RECENCY.matcher(clause);
            if(recency.find()) {recent=months(recency.group(1),recency.group(2));clause=recency.replaceFirst("");}
            var duration=DURATION.matcher(clause);
            if(duration.find()) {months=months(duration.group(1),duration.group(2));clause=duration.replaceFirst("");}
            boolean vague=Pattern.compile("\\brecente?s?\\b").matcher(clause).find();
            clause=clause.replaceAll("\\brecente?s?\\b","");
            boolean desired=Pattern.compile("\\bsouhaitees?\\b|\\bsouhaites?\\b").matcher(clause).find();
            boolean required=Pattern.compile("\\bobligatoires?\\b").matcher(clause).find();
            if(desired&&required) reject("UNSUPPORTED_SEARCH_LANGUAGE");
            clause=clause.replaceAll("\\b(?:souhaitees?|souhaites?|obligatoires?)\\b","").strip();
            if(!clause.matches("@\\d+@(?:\\s*(?:et|,)\\s*@\\d+@)*")) reject("UNSUPPORTED_SEARCH_LANGUAGE");
            var markers=Pattern.compile("@(\\d+)@").matcher(clause);var selected=new ArrayList<String>();
            while(markers.find()) selected.add(names.get(Integer.parseInt(markers.group(1))));
            if(selected.size()>1&&(months!=null||recent!=null||vague)) {
                warnings.add("Portée à préciser pour "+String.join(" et ",selected)+" : "+(months==null?"":"durée minimale de "+months+" mois. ")+(recent==null?"":"récence de "+recent+" mois. ")+"Aucun seuil appliqué au groupe : renseignez chaque compétence, ou laissez les seuils vides. La pratique conjointe et l'expérience globale ne sont pas calculées.");
                months=null;recent=null;
            } else if(vague) warnings.add("Récence de "+selected.getFirst()+" à préciser : aucune fenêtre chiffrée déduite du mot récent.");
            if(selected.size()>1&&(desired||required)) {
                warnings.add("Exigence à préciser pour "+String.join(" et ",selected)+" : le qualificatif peut porter sur plusieurs compétences. Vérifiez chaque exigence ; obligatoire est proposé par défaut.");
                desired=false;
            }
            for(String name:selected) {
                if(!seen.add(TaxonomyService.key(name))) reject("DUPLICATE_SEARCH_SKILL");
                criteria.add(new StructuredSearchService.Criterion(name,desired?StructuredSearchService.Importance.DESIRED:StructuredSearchService.Importance.REQUIRED,months,recent));
            }
        }
        if(criteria.isEmpty()||criteria.size()>10) reject("INVALID_NATURAL_SEARCH");
        return new Interpretation(List.copyOf(criteria),List.copyOf(warnings),dictionary.version(),RULE);
    }
    private static int months(String number,String unit) {
        var words=List.of("zero","un","deux","trois","quatre","cinq","six","sept","huit","neuf","dix","onze","douze");
        int n=number.equals("une")?1:words.contains(number)?words.indexOf(number):Integer.parseInt(number);
        int result=unit.equals("mois")?n:n*12;
        if(result<1||result>1200) reject("INVALID_NATURAL_SEARCH");
        return result;
    }
    private static void reject(String code) {throw new Rejected(HttpStatus.BAD_REQUEST,code);}
}
