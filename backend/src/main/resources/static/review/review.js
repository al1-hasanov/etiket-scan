'use strict';
let csrf, page=0, selected, busy=false;
const byId=id=>document.getElementById(id);
const el=(tag,text,cls)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(cls)node.className=cls;return node;};
async function api(path,body){
  const options={credentials:'same-origin',headers:{'Accept':'application/json'},cache:'no-store'};
  if(body!==undefined){options.method='POST';options.headers['Content-Type']='application/json';options.headers[csrf.headerName]=csrf.token;options.body=JSON.stringify(body);}
  const response=await fetch(path,options);let data;try{data=await response.json();}catch{data={};}
  if(!response.ok)throw new Error(response.status===403?'Session token expired. Reload the page and try again.':data.code||'Request failed ('+response.status+').');return data;
}
function message(text,error=false){byId('message').textContent=text;byId('message').className=error?'error':'success';}
async function loadQueue(){
  const status=byId('status').value;
  const data=await api('/api/v1/review/submissions?status='+encodeURIComponent(status)+'&page='+page);
  byId('queue').replaceChildren();
  for(const item of data.items){const button=el('button',item.barcode+' · '+new Date(item.submittedAt).toLocaleString());button.onclick=()=>open(item.id).catch(e=>message(e.message,true));byId('queue').append(button);}
  if(!data.items.length)byId('queue').append(el('p','No submissions here.','muted'));
  byId('page').textContent='Page '+(page+1)+' · '+data.total+' submissions';byId('previous').disabled=page===0;byId('next').disabled=(page+1)*25>=data.total;
}
function field(form,name,label,value,multiline=false){const wrap=el('div',undefined,multiline?'wide':'');const input=el(multiline?'textarea':'input');input.name=name;input.id='field-'+name;input.value=value||'';const title=el('label',label);title.htmlFor=input.id;wrap.append(title,input);form.append(wrap);return input;}
async function open(id){
  const data=await api('/api/v1/review/submissions/'+id);selected=data;
  const section=byId('detail');section.replaceChildren(el('h2',data.submitted.name));
  section.append(el('p',data.submission.barcode+' · '+data.submission.status,'muted'));
  const photos=el('div',undefined,'photos');
  for(const photo of data.photos){const figure=el('figure');const link=el('a');link.href=photo.url;link.target='_blank';link.rel='noopener';const image=el('img');image.src=photo.url;image.alt=photo.role+' package photo';link.append(image);figure.append(link,el('figcaption',photo.role+' · click for full size'));photos.append(figure);}
  section.append(photos);
  if(data.submission.status!=='PENDING'){
    section.append(el('p','Reviewed '+new Date(data.submission.reviewedAt).toLocaleString(),'muted'));
    if(data.reviewedProduct){const link=el('a','Open published label source','link');link.href=data.reviewedProduct.sourceUrl;section.append(link,el('pre',data.reviewedProduct.ingredients));}
    else section.append(el('p',data.submission.note));return;
  }
  section.append(el('p','Check barcode, exact product variant, pack size and the version sold in Azerbaijan. Transcribe the original label language. A foreign-market recipe is not evidence for the local variant.','notice'));
  if(data.currentPublishedSubmissionId)section.append(el('p','An approved record already exists for this barcode. Approval will replace the active record; its earlier source remains available.','notice'));
  const form=el('form');const fields=el('div',undefined,'fields');form.append(fields);
  const name=field(fields,'name','Product name',data.submitted.name);name.required=true;name.maxLength=200;
  const brand=field(fields,'brand','Brand',data.submitted.brand);brand.maxLength=200;
  const quantity=field(fields,'quantity','Pack size / quantity',data.submitted.quantity);quantity.required=true;quantity.maxLength=80;
  const language=field(fields,'ingredientsLanguage','Label language code (az, en, ru, tr…)',data.submitted.ingredientsLanguage);language.required=true;language.pattern='[a-z]{2,3}(-[A-Z]{2})?';language.maxLength=12;
  const ingredients=field(fields,'ingredients','Ingredients — exact transcription',data.submitted.ingredients,true);ingredients.required=true;ingredients.maxLength=10000;
  const allergens=field(fields,'declaredAllergenStatement','Allergen / trace statement — exact wording, leave blank if unknown',data.submitted.declaredAllergenStatement,true);allergens.maxLength=2000;
  for(const [role,analysis] of Object.entries(data.analysis||{})){
    const audit=el('details',undefined,'wide');audit.append(el('summary','Original automatic reading — '+role));
    audit.append(el('p','Unreviewed machine output. Compare it with the photo and the edited fields.','notice'),el('pre',analysis.rawText));
    if(analysis.uncertainWords?.length)audit.append(el('p','Words to check: '+analysis.uncertainWords.join(', '),'muted'));
    form.append(audit);
  }
  const nutritionTitle=el('h3','Nutrition — exact label values');form.append(nutritionTitle);
  const nutritionGrid=el('div',undefined,'fields');form.append(nutritionGrid);
  const basisWrap=el('div');const basis=el('select');basis.id='nutrition-basis';
  for(const [value,title] of [['','Unknown / no values'],['PER_100_G','Per 100 g'],['PER_100_ML','Per 100 ml']]){const option=el('option',title);option.value=value;basis.append(option);}
  basis.value=data.submitted.nutrition?.basis||'';const basisLabel=el('label','Reference amount printed on the label');basisLabel.htmlFor=basis.id;basisWrap.append(basisLabel,basis);nutritionGrid.append(basisWrap);
  const nutritionFields={};
  for(const [key,label] of [['energyKcal','Energy (kcal)'],['fat','Fat (g)'],['saturatedFat','Saturated fat (g)'],['carbohydrates','Carbohydrates (g)'],['sugars','Sugars (g)'],['fiber','Fibre (g)'],['protein','Protein (g)'],['salt','Salt (g)']]){
    const input=field(nutritionGrid,key,label,String(data.submitted.nutrition?.values?.[key]??''));input.type='number';input.min='0';input.max=key==='energyKcal'?'2000':'200';input.step='any';nutritionFields[key]=input;
  }
  form.append(el('p','Keep unknown values blank. Preserve 100 g versus 100 ml. Do not copy a per-serving column or convert sodium to salt automatically.','muted'));
  const nutritionCheck=el('input');nutritionCheck.type='checkbox';const nutritionConsent=el('label',undefined,'check');nutritionConsent.append(nutritionCheck,el('span','I compared the nutrition-table photo, reference amount, units and every entered value.'));form.append(nutritionConsent);
  const consent=el('label',undefined,'check');const checked=el('input');checked.type='checkbox';checked.required=true;consent.append(checked,el('span','I compared the package photos and confirmed the barcode, pack variant and Azerbaijan market version. The transcription matches the label, and these photos can be published as its source.'));form.append(consent);
  const actions=el('div',undefined,'actions');const approve=el('button','Approve and publish','primary');approve.type='submit';actions.append(approve);form.append(actions);
  form.onsubmit=async event=>{event.preventDefault();if(busy||!form.reportValidity())return;busy=true;approve.disabled=true;
    try{const values={};for(const [key,input] of Object.entries(nutritionFields))if(input.value.trim()!=='')values[key]=Number(input.value);if(Object.keys(values).length&&(!basis.value||!nutritionCheck.checked))throw new Error('Confirm the nutrition reference amount and photo comparison.');await api('/api/v1/review/submissions/'+id+'/approve',{version:data.version,name:name.value,brand:brand.value,quantity:quantity.value,ingredients:ingredients.value,ingredientsLanguage:language.value,declaredAllergenStatement:allergens.value,expectedPublishedSubmissionId:data.currentPublishedSubmissionId,photosChecked:checked.checked,nutrition:{basis:basis.value,values},nutritionPhotoChecked:nutritionCheck.checked});message('Published. A new scan and catalogue refresh will return the reviewed ingredients.');await loadQueue();await open(id);}catch(e){message(e.message,true);}finally{busy=false;approve.disabled=false;}};
  section.append(form);
  const rejectSection=el('div');rejectSection.append(el('h3','Reject this submission'));
  const note=el('textarea');note.placeholder='Explain what needs a clearer photo or correction.';note.maxLength=1000;note.setAttribute('aria-label','Rejection reason');rejectSection.append(note);
  const reject=el('button','Reject','danger');reject.onclick=async()=>{if(busy)return;if(!note.value.trim()){message('Enter a rejection reason.',true);return;}busy=true;reject.disabled=true;
    try{await api('/api/v1/review/submissions/'+id+'/reject',{version:data.version,note:note.value});message('Rejected. Published product information is unchanged.');await loadQueue();await open(id);}catch(e){message(e.message,true);}finally{busy=false;reject.disabled=false;}};
  rejectSection.append(reject);section.append(rejectSection);
}
byId('status').onchange=()=>{page=0;loadQueue().catch(e=>message(e.message,true));};
byId('previous').onclick=()=>{if(page>0)page--;loadQueue().catch(e=>message(e.message,true));};
byId('next').onclick=()=>{page++;loadQueue().catch(e=>message(e.message,true));};
const nutritionFile = byId('nutrition-file');
const previewImport = byId('preview-import');
const applyImport = byId('apply-import');
const importMessage = byId('import-message');
const importConflicts = byId('import-conflicts');
let previewedFeed;
nutritionFile.onchange = () => {
  previewedFeed = undefined;
  applyImport.disabled = true;
  previewImport.disabled = !csrf || !nutritionFile.files.length;
  importMessage.textContent = '';
  importConflicts.replaceChildren();
};
async function importNutrition(dryRun) {
  const file = nutritionFile.files[0];
  previewImport.disabled = applyImport.disabled = nutritionFile.disabled = true;
  importMessage.textContent = dryRun ? 'Checking feed…' : 'Applying feed…';
  importMessage.className = '';
  importConflicts.replaceChildren();
  try {
    if (!file || file.size > 1024 * 1024) throw new Error('Choose a JSON feed up to 1 MB.');
    const feed = dryRun ? JSON.parse(await file.text()) : previewedFeed;
    const result = await api('/api/v1/review/nutrition-import?dryRun=' + dryRun, feed);
    previewedFeed = dryRun ? feed : undefined;
    importMessage.textContent = (result.applied ? 'Applied ' : 'Previewed ') + result.records
      + ' reports. ' + result.conflicts.length + ' need review.';
    for (const conflict of result.conflicts) {
      const reason = {
        PACK_SIZE_UNKNOWN: 'Catalogue pack size is unclear',
        PACK_SIZE_MISMATCH: 'Pack sizes differ',
        NUTRITION_DISAGREEMENT: 'Nutrition panels disagree'
      }[conflict.reason] || conflict.reason;
      importConflicts.append(el('li', conflict.barcode + ' · ' + reason));
    }
  } catch (error) {
    previewedFeed = undefined;
    importMessage.textContent = error.message;
    importMessage.className = 'error';
  } finally {
    nutritionFile.disabled = false;
    previewImport.disabled = !nutritionFile.files.length;
    applyImport.disabled = !previewedFeed;
  }
}
previewImport.onclick = () => importNutrition(true);
applyImport.onclick = () => importNutrition(false);
(async()=>{try{csrf=await api('/api/v1/review/csrf');nutritionFile.onchange();await loadQueue();}catch(e){message(e.message,true);}})();
