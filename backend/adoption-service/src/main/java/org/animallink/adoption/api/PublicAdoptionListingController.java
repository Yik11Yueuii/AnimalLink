package org.animallink.adoption.api;
import java.time.Instant;import java.util.List;
import org.animallink.adoption.application.AdoptionListingService;import org.animallink.adoption.domain.*;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/public/listings")
public class PublicAdoptionListingController {
 private final AdoptionListingService service;public PublicAdoptionListingController(AdoptionListingService service){this.service=service;}
 @GetMapping public Page list(@RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size){var r=service.publicListings(page,size);return new Page(r.items().stream().map(Item::from).toList(),r.page(),r.size(),r.total());}
 @GetMapping("/{id}") public Item detail(@PathVariable("id") String id){return Item.from(service.publicDetail(id));}
 record Item(String listingId,String animalId,String title,String description,Instant publishedAt){static Item from(AdoptionListing v){return new Item(v.id(),v.animalId(),v.title(),v.description(),v.publishedAt());}}
 record Page(List<Item> items,int page,int size,long total){}
}
