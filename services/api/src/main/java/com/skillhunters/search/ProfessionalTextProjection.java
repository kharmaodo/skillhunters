package com.skillhunters.search;

import com.skillhunters.documents.StructuredExtractor.Field;
import com.skillhunters.taxonomy.TaxonomyService;
import java.util.*;
import java.util.regex.Pattern;

/** Allowlisted vocabulary projection; never forwards a narrative or identity to the model. */
public final class ProfessionalTextProjection {
    public static final String VERSION="professional-dictionary-mentions-v1";
    private static final Pattern TOKEN=Pattern.compile("[\\p{L}\\p{N}_+#.]+");
    private static final Set<Field> FIELDS=Set.of(Field.TITLE,Field.EXPERIENCE,Field.EDUCATION,Field.CERTIFICATION_DECLARATION);
    private static final class Node {final Map<String,Node> children=new HashMap<>();final Set<String> labels=new TreeSet<>();}
    private final Node root=new Node();
    public ProfessionalTextProjection(TaxonomyService.Dictionary dictionary) {
        for(var skill:dictionary.skills()) {
            if(FrenchSearchInterpreter.forbidden(skill.name())) continue;
            var terms=new ArrayList<>(skill.aliases());terms.add(skill.name());
            for(String term:terms) {
                if(FrenchSearchInterpreter.forbidden(term)) continue;
                var tokens=tokens(term);if(tokens.isEmpty()) continue;
                Node node=root;for(String token:tokens) node=node.children.computeIfAbsent(token,k->new Node());
                node.labels.add(skill.name());
            }
        }
    }
    public Set<String> labels(Field field,String text) {
        if(!FIELDS.contains(field)||text==null||text.contains("@")||FrenchSearchInterpreter.forbidden(text)) return Set.of();
        var tokens=tokens(text);var result=new LinkedHashSet<String>();
        for(int i=0;i<tokens.size();i++) {
            Node node=root;Set<String> longest=Set.of();int end=i;
            for(int j=i;j<tokens.size();j++) {
                node=node.children.get(tokens.get(j));if(node==null) break;
                if(!node.labels.isEmpty()) {longest=node.labels;end=j;}
            }
            result.addAll(longest);if(!longest.isEmpty()) i=end;
        }
        return Set.copyOf(result);
    }
    private static List<String> tokens(String text) {
        var result=new ArrayList<String>();var matcher=TOKEN.matcher(TaxonomyService.key(text));
        while(matcher.find()) result.add(matcher.group().replaceAll("\\.+$",""));
        return result;
    }
}
