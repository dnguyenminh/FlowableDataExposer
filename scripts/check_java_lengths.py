import os, re, json
root_dirs=['core/src/main/java','web/src/main/java','complexSample/src/main/java']
files_over_200=[]
methods_over_20=[]
# naive method start regex
method_def_re=re.compile(r'^\s*(public|private|protected)\s+[\w\<\>\[\]]+\s+(\w+)\s*\([^;{]*\)\s*\{')
for root in root_dirs:
    if not os.path.isdir(root):
        continue
    for dirpath,_,filenames in os.walk(root):
        for fn in filenames:
            if not fn.endswith('.java'): continue
            path=os.path.join(dirpath,fn)
            try:
                with open(path,'r',encoding='utf-8') as f:
                    lines=f.readlines()
            except Exception as e:
                continue
            total=len(lines)
            if total>200:
                files_over_200.append({'path':path.replace('\\','/'),'lines':total})
            i=0
            while i<len(lines):
                m=method_def_re.match(lines[i])
                if m:
                    method_name=m.group(2)
                    # find method end by brace counting
                    brace_count=0
                    start=i
                    end=i
                    for j in range(i, len(lines)):
                        brace_count += lines[j].count('{') - lines[j].count('}')
                        if brace_count==0:
                            end=j
                            break
                    length=end-start+1
                    if length>20:
                        methods_over_20.append({'path':path.replace('\\','/'),'method':method_name,'start_line':start+1,'length':length})
                    i=end+1
                else:
                    i+=1
out={'files_over_200':sorted(files_over_200,key=lambda x:-x['lines']),'methods_over_20':sorted(methods_over_20,key=lambda x:-x['length'])}
print(json.dumps(out,indent=2))
