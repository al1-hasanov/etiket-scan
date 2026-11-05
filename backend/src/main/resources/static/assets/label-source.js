'use strict';
(async()=>{
  const root=document.getElementById('source');const el=(tag,text,cls)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(cls)node.className=cls;return node;};
  try{
    const id=location.pathname.split('/').filter(Boolean).pop();
    const response=await fetch('/api/v1/labels/'+encodeURIComponent(id),{cache:'no-store'});if(!response.ok)throw new Error();
    const data=await response.json(),product=data.product;
    const details=el('section',undefined,'card');details.append(el('h2',product.name),el('p',product.brand+' · '+product.quantity+' · '+product.barcode,'muted'),el('p','Yoxlanma tarixi: '+new Date(data.reviewedAt).toLocaleString(),'muted'),el('h3','Tərkib — orijinal mətn'),el('pre',product.ingredients));
    if(product.evidence.declaredAllergenStatement)details.append(el('h3','Etiketdəki allergen məlumatı'),el('pre',product.evidence.declaredAllergenStatement));
    details.append(el('p','Məlumat göndərilmiş qablaşdırma fotoları ilə müqayisə edilib. İstehsalçı tərkibi dəyişə bilər; aldığınız məhsulun cari etiketini yoxlayın.','notice'));
    if(product.nutrition&&Object.keys(product.nutrition.values||{}).length){
      details.append(el('h3','Qida dəyərləri — '+(product.nutrition.basis==='PER_100_ML'?'100 ml':'100 q')));
      const names={energyKcal:'Enerji (kcal)',fat:'Yağ (q)',saturatedFat:'Doymuş yağ (q)',carbohydrates:'Karbohidrat (q)',sugars:'Şəkərlər (q)',fiber:'Lif (q)',protein:'Zülal (q)',salt:'Duz (q)'};
      for(const [key,value] of Object.entries(product.nutrition.values))details.append(el('p',(names[key]||key)+': '+value));
    }
    const photos=el('section',undefined,'photos');const labels={nutrition:'Qida dəyərləri',front:'Ön tərəf',barcode:'Barkod',ingredients:'Tərkib etiketi'};
    for(const photo of data.photos){const figure=el('figure',undefined,'card');const link=el('a');link.href=photo.url;link.target='_blank';link.rel='noopener';const image=el('img');image.src=photo.url;image.alt=labels[photo.role]||photo.role;link.append(image);figure.append(link,el('figcaption',image.alt));photos.append(figure);}
    root.replaceChildren(details,photos);
  }catch{root.replaceChildren(el('p','Bu etiket mənbəsi tapılmadı və ya hələ təsdiqlənməyib.','error'));}
})();
