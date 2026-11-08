package org.animallink.animal.infrastructure;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
@Component
public class AnimalMiniappAssertionFilter extends OncePerRequestFilter {
 public static final String PRINCIPAL=AnimalMiniappAssertionFilter.class.getName()+".principal";
 private final AnimalMiniappAssertionVerifier verifier;
 public AnimalMiniappAssertionFilter(AnimalMiniappAssertionVerifier verifier){this.verifier=verifier;}
 @Override protected boolean shouldNotFilter(HttpServletRequest request){return !AnimalMiniappRequestPolicy.isProtected(request.getRequestURI());}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  try{var user=verifier.verify(request.getHeader("Authorization"),request.getMethod(),request.getRequestURI());
   request.setAttribute(PRINCIPAL,user);chain.doFilter(request,response);
  }catch(AnimalMiniappAssertionVerifier.Unavailable e){response.sendError(503);}catch(AnimalMiniappAssertionVerifier.Forbidden e){response.sendError(403);}catch(AnimalMiniappAssertionVerifier.Rejected e){response.sendError(401);}
 }
}
