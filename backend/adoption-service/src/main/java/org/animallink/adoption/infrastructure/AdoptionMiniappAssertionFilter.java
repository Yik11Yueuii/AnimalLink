package org.animallink.adoption.infrastructure;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
@Component
public class AdoptionMiniappAssertionFilter extends OncePerRequestFilter {
 public static final String PRINCIPAL=AdoptionMiniappAssertionFilter.class.getName()+".principal";
 private final AdoptionMiniappAssertionVerifier verifier;
 public AdoptionMiniappAssertionFilter(ObjectProvider<AdoptionMiniappAssertionVerifier> provider){this.verifier=provider.getIfAvailable();}
 @Override protected boolean shouldNotFilter(HttpServletRequest request){return !AdoptionMiniappRequestPolicy.isProtected(request.getRequestURI());}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  try{if(verifier==null)throw new AdoptionMiniappAssertionVerifier.Unavailable();var user=verifier.verify(request.getHeader("Authorization"),request.getMethod(),request.getRequestURI());
   request.setAttribute(PRINCIPAL,user);chain.doFilter(request,response);
  }catch(AdoptionMiniappAssertionVerifier.Unavailable e){response.sendError(503);}catch(AdoptionMiniappAssertionVerifier.Forbidden e){response.sendError(403);}catch(AdoptionMiniappAssertionVerifier.Rejected e){response.sendError(401);}
 }
}
