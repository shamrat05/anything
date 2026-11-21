package dev.fastsearch.android;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.style.BackgroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.ext.gfm.tables.*;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;

/** Immutable native spans; no browser, scripts, image fetches or Activity references. */
final class RichText {
    private static final Parser MARKDOWN=Parser.builder().extensions(Collections.singletonList(TablesExtension.create())).build();
    static Spanned markdown(String source){Renderer renderer=new Renderer();MARKDOWN.parse(source).accept(renderer);return new SpannedString(renderer.out);}
    static Spanned html(String source){SpannableStringBuilder text=new SpannableStringBuilder(android.text.Html.fromHtml(source,android.text.Html.FROM_HTML_MODE_COMPACT));
        for(URLSpan span:text.getSpans(0,text.length(),URLSpan.class)){int start=text.getSpanStart(span),end=text.getSpanEnd(span);text.removeSpan(span);if(safeLink(span.getURL()))text.setSpan(new SafeLink(span.getURL()),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}return new SpannedString(text);}
    static boolean safeLink(String value){String uri=value.trim().toLowerCase(Locale.ROOT);return uri.startsWith("https://")||uri.startsWith("http://")||uri.startsWith("mailto:");}
    private static final class SafeLink extends URLSpan {
        SafeLink(String url){super(url.trim());}
        @Override public void onClick(android.view.View view){try{view.getContext().startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(getURL())));}
            catch(android.content.ActivityNotFoundException|SecurityException unavailable){android.widget.Toast.makeText(view.getContext(),"No app can open this link",android.widget.Toast.LENGTH_SHORT).show();}}
    }
    private static final class Renderer extends AbstractVisitor {
        final SpannableStringBuilder out=new SpannableStringBuilder();
        final IdentityHashMap<OrderedList,Integer> numbers=new IdentityHashMap<>();
        int depth;
        void space(int lines){int present=0;for(int i=out.length()-1;i>=0&&out.charAt(i)=='\n';i--)present++;for(int n=present;n<lines;n++)out.append('\n');}
        void span(Object value,int start){if(out.length()>start)out.setSpan(value,start,out.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
        void styled(Node node,Object style){int start=out.length();visitChildren(node);span(style,start);}
        void code(String value){space(1);int start=out.length();out.append(value);span(new TypefaceSpan("monospace"),start);span(new RelativeSizeSpan(.9f),start);span(new BackgroundColorSpan(0x16808080),start);space(2);}
        @Override public void visit(Text node){out.append(node.getLiteral());}
        @Override public void visit(SoftLineBreak node){out.append(' ');}
        @Override public void visit(HardLineBreak node){out.append('\n');}
        @Override public void visit(Paragraph node){visitChildren(node);space(node.getParent() instanceof ListItem?1:2);}
        @Override public void visit(Heading node){if(out.length()>0)space(2);int start=out.length();visitChildren(node);span(new StyleSpan(Typeface.BOLD),start);span(new RelativeSizeSpan(node.getLevel()==1?1.6f:node.getLevel()==2?1.35f:1.15f),start);space(2);}
        @Override public void visit(Emphasis node){styled(node,new StyleSpan(Typeface.ITALIC));}
        @Override public void visit(StrongEmphasis node){styled(node,new StyleSpan(Typeface.BOLD));}
        @Override public void visit(Code node){int start=out.length();out.append(node.getLiteral());span(new TypefaceSpan("monospace"),start);span(new BackgroundColorSpan(0x16808080),start);}
        @Override public void visit(FencedCodeBlock node){code(node.getLiteral());}
        @Override public void visit(IndentedCodeBlock node){code(node.getLiteral());}
        @Override public void visit(BlockQuote node){space(1);int start=out.length();visitChildren(node);span(new QuoteSpan(0xFF9299AA),start);space(2);}
        @Override public void visit(BulletList node){depth++;visitChildren(node);depth--;if(depth==0)space(2);}
        @Override public void visit(OrderedList node){numbers.put(node,node.getStartNumber());depth++;visitChildren(node);depth--;numbers.remove(node);if(depth==0)space(2);}
        @Override public void visit(ListItem node){space(1);int start=out.length();Node parent=node.getParent();
            if(parent instanceof OrderedList){int number=numbers.get((OrderedList)parent);out.append(Integer.toString(number)).append(". ");numbers.put((OrderedList)parent,number+1);}else out.append("• ");
            visitChildren(node);span(new LeadingMarginSpan.Standard(Math.max(0,depth-1)*24,Math.max(0,depth-1)*24+24),start);}
        @Override public void visit(Link node){int start=out.length();visitChildren(node);if(safeLink(node.getDestination()))span(new SafeLink(node.getDestination()),start);}
        @Override public void visit(Image node){out.append('[');visitChildren(node);out.append(']');}
        @Override public void visit(HtmlInline node){out.append(node.getLiteral());}
        @Override public void visit(HtmlBlock node){code(node.getLiteral());}
        @Override public void visit(ThematicBreak node){space(2);out.append("────────");space(2);}
        @Override public void visit(CustomBlock node){visitChildren(node);space(2);}
        @Override public void visit(CustomNode node){int start=out.length();visitChildren(node);
            if(node instanceof TableCell){if(((TableCell)node).isHeader())span(new StyleSpan(Typeface.BOLD),start);if(node.getNext()!=null)out.append("    ");}
            else if(node instanceof TableRow)space(1);}
    }
    private RichText(){}
}
