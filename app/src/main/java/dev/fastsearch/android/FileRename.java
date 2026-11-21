package dev.fastsearch.android;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import dev.fastsearch.core.FileEntry;
import dev.fastsearch.core.RenameRules;
import java.util.*;

/** A bounded, live name preview backed by an identity-bound selection. */
final class FileRename {
    static AppSheet show(Activity activity,List<FileEntry> entries,boolean root,java.util.concurrent.ExecutorService executor,Runnable accepted){
        if(activity.isFinishing()||activity.isDestroyed())return null;
        SelectionPreparation preparation=new SelectionPreparation();
        AppSheet sheet=new AppSheet(activity,entries.size()==1?"Rename":"Rename "+entries.size()+" items");
        Spinner mode=new Spinner(activity);
        String[] modes=entries.size()==1?new String[]{"Name","Replace","Add text","Number"}:new String[]{"Replace","Add text","Number"};
        mode.setAdapter(new ArrayAdapter<String>(activity,android.R.layout.simple_spinner_dropdown_item,modes){
            @Override public View getView(int position,View recycled,android.view.ViewGroup parent){TextView view=(TextView)super.getView(position,recycled,parent);style(view);return view;}
            @Override public View getDropDownView(int position,View recycled,android.view.ViewGroup parent){TextView view=(TextView)super.getDropDownView(position,recycled,parent);style(view);return view;}
            private void style(TextView view){view.setTypeface(activity.getResources().getFont(R.font.aeonik));view.setTextColor(sheet.theme.ink);view.setTextSize(14);}
        });sheet.content.addView(mode,new LinearLayout.LayoutParams(-1,sheet.dp(48)));
        EditText first=field(sheet,"Name"),second=field(sheet,"Replacement");
        LinearLayout numbers=new LinearLayout(activity);EditText start=field(sheet,"Start"),digits=field(sheet,"Digits");sheet.content.removeView(start);sheet.content.removeView(digits);
        start.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);digits.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);start.setText("1");digits.setText("3");
        numbers.addView(start,new LinearLayout.LayoutParams(0,sheet.dp(48),1));numbers.addView(digits,new LinearLayout.LayoutParams(0,sheet.dp(48),1));sheet.content.addView(numbers);
        CheckBox extension=new CheckBox(activity);extension.setText("Keep extension");extension.setChecked(true);extension.setTextColor(sheet.theme.ink);extension.setTypeface(activity.getResources().getFont(R.font.aeonik));sheet.content.addView(extension);
        TextView preview=sheet.text("",13,sheet.theme.ink);preview.setPadding(0,sheet.dp(12),0,0);sheet.content.addView(preview);
        ProgressBar preparing=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);preparing.setIndeterminate(true);preparing.setContentDescription("Preparing rename");sheet.content.addView(preparing,new LinearLayout.LayoutParams(-1,sheet.dp(2)));
        sheet.action("Cancel",false,false,null);Button apply=sheet.action("Rename",true,false,null);
        final java.util.concurrent.atomic.AtomicReference<Map<String,String>> planned=new java.util.concurrent.atomic.AtomicReference<>(Collections.emptyMap());
        Runnable update=()->{
            try{
                RenameRules.Mode selected=RenameRules.Mode.values()[mode.getSelectedItemPosition()+(entries.size()==1?0:1)];
                first.setHint(selected==RenameRules.Mode.NAME?"Name":selected==RenameRules.Mode.REPLACE?"Find":"Prefix");
                second.setHint(selected==RenameRules.Mode.REPLACE?"Replace with":"Suffix");first.setContentDescription(first.getHint());second.setContentDescription(second.getHint());
                second.setVisibility(selected==RenameRules.Mode.NAME?View.GONE:View.VISIBLE);numbers.setVisibility(selected==RenameRules.Mode.NUMBER?View.VISIBLE:View.GONE);extension.setVisibility(selected==RenameRules.Mode.NAME?View.GONE:View.VISIBLE);
                Map<String,String> plan=RenameRules.plan(entries,selected,first.getText().toString(),second.getText().toString(),selected==RenameRules.Mode.NUMBER?Integer.parseInt(start.getText().toString()):1,selected==RenameRules.Mode.NUMBER?Integer.parseInt(digits.getText().toString()):3,extension.isChecked());
                StringBuilder lines=new StringBuilder();int changes=0;
                for(FileEntry entry:entries){String name=plan.get(entry.path());if(!name.equals(entry.name))changes++;if(lines.length()<6000){if(lines.length()>0)lines.append("\n\n");lines.append(entry.name).append("\n").append(name);}}
                if(lines.length()>=6000)lines.append("\n\nMore items included");
                preview.setText(lines);preview.setTextColor(sheet.theme.ink);planned.set(plan);boolean ready=changes>0&&preparation.snapshot!=null;apply.setEnabled(ready);apply.setAlpha(ready?1:.4f);
            }catch(IllegalArgumentException failure){planned.set(Collections.emptyMap());preview.setText(failure instanceof NumberFormatException?"Enter a starting number and digits.":failure.getMessage());preview.setTextColor(sheet.theme.muted);apply.setEnabled(false);apply.setAlpha(.4f);}
        };
        TextWatcher watcher=new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){update.run();}public void afterTextChanged(Editable e){}};
        for(EditText field:new EditText[]{first,second,start,digits})field.addTextChangedListener(watcher);
        extension.setOnCheckedChangeListener((v,checked)->update.run());mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int position,long id){update.run();}public void onNothingSelected(AdapterView<?> p){}});
        apply.setOnClickListener(v->{try{FileOperationService.rename(activity,preparation.snapshot,planned.get());sheet.dismiss();accepted.run();}catch(Exception failure){preview.setText(failure.getMessage());}});
        if(entries.size()==1){String name=entries.get(0).name;first.setText(name);int end=entries.get(0).directory?name.length():name.lastIndexOf('.');first.setSelection(0,end>0?end:name.length());}
        update.run();sheet.show();preparation.start(sheet,entries,root,executor,()->{preparing.setVisibility(View.GONE);update.run();if(preparation.error!=null){preview.setText(preparation.error);apply.setEnabled(false);}});return sheet;
    }
    private static EditText field(AppSheet sheet,String hint){EditText field=new EditText(sheet.getContext());field.setSingleLine(true);field.setTextSize(15);field.setTypeface(sheet.getContext().getResources().getFont(R.font.aeonik));field.setTextColor(sheet.theme.ink);field.setHintTextColor(sheet.theme.muted);field.setHint(hint);field.setContentDescription(hint);sheet.content.addView(field,new LinearLayout.LayoutParams(-1,sheet.dp(48)));return field;}
    private FileRename(){}
}
