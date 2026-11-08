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
 private final AnimalMiniappAssertionVerifier verifier;
 public AnimalMiniappAssertionFilter(AnimalMiniappAssertionVerifier verifier){this.verifier=verifier;}
 @Override protected boolean shouldNotFilter(HttpServletRequest request){return !AnimalMiniappRequestPolicy.isProtected(request.getRequestURI());}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  try{var user=verifier.verify(request.getHeader("Authorization"),request.getMethod(),request.getRequestURI());
   var safe=new HttpServletRequestWrapper(request){
    @Override public String getHeader(String name){return name.equalsIgnoreCase("X-User-Id")?user.userId():super.getHeader(name);}
    @Override public Enumeration<String> getHeaders(String name){return name.equalsIgnoreCase("X-User-Id")?Collections.enumeration(List.of(user.userId())):super.getHeaders(name);}
   };chain.doFilter(safe,response);
  }catch(AnimalMiniappAssertionVerifier.Unavailable e){response.sendError(503);}catch(AnimalMiniappAssertionVerifier.Forbidden e){response.sendError(403);}catch(AnimalMiniappAssertionVerifier.Rejected e){response.sendError(401);}
 }
}
