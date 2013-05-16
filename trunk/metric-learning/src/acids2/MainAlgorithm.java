package acids2;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.TreeSet;

import libsvm.svm;
import libsvm.svm_model;
import libsvm.svm_node;
import libsvm.svm_parameter;
import libsvm.svm_problem;
import utility.GammaComparator;
import utility.ValueParser;
import acids2.plot.Svm3D;
import acids2.test.Test;
import filters.StandardFilter;
import filters.WeightedNgramFilter;
import filters.mahalanobis.MahalaFilter;
import filters.reeding.NewNgFilter;

/**
 * @author Tommaso Soru <tsoru@informatik.uni-leipzig.de>
 *
 */
public class MainAlgorithm {
		
	// SVM parameters
	private static svm_model model;
	private static svm_problem problem;
	private static int KERNEL = svm_parameter.POLY;
	private static final int DEGREE = 2;
	private static final int COEF0 = 0;
	private static final double GAMMA = 1;
	
	// classifier properties
	private static double[][] sv_d;
	private static double[] w;
	private static double[] w_linear;
	private static double theta0;
	private static double theta;
	private static double theta_plot;
	private static double beta;
	private static int n;
	/*
	 * XXX is there a correlation between th0c and the similarity mean for each property?
	 * 
	 * TODO create Settings static class
	 * TODO save similarity to array for Octave
	 */
	
	// total number of queries
	private static int max_queries;
	// maximum number of iterations for perceptron
	private static int max_iter_perceptron;
	// queries per iteration per class (pos|neg)
	private static int k = 5;
	
	// support variables
	private static int query_counter = 0;
	private static double tp0 = 0, tn0 = 0, fp0 = 0, fn0 = 0;
	private static svm_node[][] orig_x; 

	private static ArrayList<Property> props = new ArrayList<Property>();
    private static HashMap<Integer, ArrayList<Double>> extrema = new HashMap<Integer, ArrayList<Double>>();

    
    public static void start(TreeSet<Resource> sources, TreeSet<Resource> targets, int _queries, double _beta, double _th0c,
    		int _mip) {
		long t0 = System.currentTimeMillis(); 
				
    	max_queries = _queries;
    	
    	max_iter_perceptron = _mip;
		// initialization
		
		ArrayList<String> propertyNames;
		try {
			propertyNames = sources.first().getPropertyNames();
		} catch (NoSuchElementException e) {
			System.err.println("Source set is empty!");
			return;
		}
		
		int index = 0;
		for(String pn : propertyNames) {
			int cnt = 0, type = Property.TYPE_NUMERIC;
			for(Resource s : sources) {
				if(s.checkDatatype(pn) == Property.TYPE_STRING) {
					type = Property.TYPE_STRING;
					break;
				}
				cnt++;
				if(cnt == 10) {
					type = Property.TYPE_NUMERIC;
					break;
				}
			}
			Property p = new Property(pn, type, index);
			props.add(p);
			
			index++;
		}
		
		for(Property p : props) {
			System.out.println(p.getName()+"\t"+p.getDatatypeAsString());
			if(p.getDatatype() == Property.TYPE_NUMERIC)
				computeExtrema(p.getIndex(), sources, targets);
		}
		
		n = propertyNames.size();
		w = new double[n];
		for(int i=0; i<n; i++)
			w[i] = 1.0;
		theta0 = (double) n * _th0c;
		theta = theta0;
		
		long prTime = System.currentTimeMillis() - t0, alTime = 0, mlTime = 0;
		
		// algorithm
		
		ArrayList<Couple> labelled = new ArrayList<Couple>();

		for(int i_loop=0; query_counter<max_queries; i_loop++) { // huge loop
			
			System.out.println("\nGrand loop: iteration #"+(i_loop+1));
			
			long pr0 = System.currentTimeMillis();
			
			TreeSet<Couple> intersection = new TreeSet<Couple>();
			beta = _beta;
			
			big: while(true) {
				
				boolean allInfinite;
				while(true) {
					intersection.clear();
					boolean performCartesianP = true;
					allInfinite = true;
					for(int i=0; i<n; i++) {
						Property p = props.get(i);
						double theta_i = computeMonteCarlo(i); // computeThreshold(i);
						System.out.println("Property: "+p.getName()+"\ttheta_"+i+" = "+theta_i);
						if(!p.isNoisy()) {
							allInfinite = false;
							if(performCartesianP) { // first property works on the entire Cartesian product.
								intersection = p.getFilter().filter(sources, targets, p.getName(), theta_i);
								performCartesianP = false;
							} else
								merge(intersection, p.getFilter().filter(intersection, p.getName(), theta_i), i);
						}
						System.out.println("intersection size: "+intersection.size());
					}
					if(allInfinite) { // solves the problem when there are no thresholds
						Property p = props.get(0);
						double theta_i = theta0 - beta - n + 1;
						System.out.println("Default. Property: "+p.getName()+"\ttheta_"+0+" = "+theta_i);
						intersection = p.getFilter().filter(sources, targets, p.getName(), theta_i);
						p.setNoisy(false);
						System.out.println("intersection size: "+intersection.size());
					}
					if(blockingEndCondition(intersection.size(), sources.size()*targets.size()))
						break;
					else {
						beta = beta + _beta;
						System.out.println("Broadening beta ("+beta+")");
					}
				}

				for(int i=0; i<n; i++) {
					Property p = props.get(i);
					if(p.isNoisy()) {
						for(Couple c : intersection) {
							Resource s = c.getSource();
							Resource t = c.getTarget();
							double d = props.get(i).getFilter().getDistance(s.getPropertyValue(p.getName()), t.getPropertyValue(p.getName()));
							c.setDistance(d, p.getIndex());
						}
					}
				}
				
				normalizeNumValues(intersection);
				
				ArrayList<Couple> posInformative = new ArrayList<Couple>();
				ArrayList<Couple> negInformative = new ArrayList<Couple>();
				
				for(Couple c : intersection) {
					double gamma = computeGamma( c.getDistances() );
					if(Double.isNaN(gamma))
						c.info();
			        c.setGamma( gamma );
			        
					if(classify(c))
						posInformative.add(c);
					else
						negInformative.add(c);
				}
				System.out.println("theta = "+theta+"\tpos = "+posInformative.size()+"\tneg = "+negInformative.size());
				
				Collections.sort(posInformative, new GammaComparator());
				Collections.sort(negInformative, new GammaComparator());
						
				TreeSet<Couple> posMostInformative = new TreeSet<Couple>();
				TreeSet<Couple> negMostInformative = new TreeSet<Couple>();
				
				for(int i=0; posMostInformative.size() < k && i != posInformative.size(); i++) {
					Couple c = posInformative.get(i);
					if(!labelled.contains(c))
						posMostInformative.add(c);
				}
				for(int i=0; negMostInformative.size() < k && i != negInformative.size(); i++) {
					Couple c = negInformative.get(i);
					if(!labelled.contains(c))
						negMostInformative.add(c);
				}
				
				long al0 = System.currentTimeMillis();
				prTime += al0 - pr0;
				
				// active learning phase
				ArrayList<Couple> poslbl = new ArrayList<Couple>();
				ArrayList<Couple> neglbl = new ArrayList<Couple>();
				
				for(Couple c : labelled)
					if(c.isPositive())
						poslbl.add(c);
					else
						neglbl.add(c);
				
				for(Couple c : posMostInformative)
					if(askOracle(c))
						poslbl.add(c);
					else
						neglbl.add(c);
				for(Couple c : negMostInformative)
					if(askOracle(c))
						poslbl.add(c);
					else
						neglbl.add(c);
		
				// search for one more pos/neg example if there are none
				int n_added = 0;
				loop: while(poslbl.size() < 1) {
					f0r: for(Couple c : posInformative)
						if(!poslbl.contains(c) && !neglbl.contains(c)) {
							if(askOracle(c) == true) {
								poslbl.add(c);
								continue loop;
							} else {
								n_added++;
								neglbl.add(c);
								if(n_added == 2*k)
									break f0r;
							}
						}
					System.out.println("Labeled pos: "+poslbl.size());
					System.out.println("Labeled neg: "+neglbl.size());
					System.out.print("Too few positives found. ");
					if(model == null) {
						theta = theta + _beta;
						System.out.println("Shifting theta ("+theta+")");
					} else {
						beta = beta + _beta;
						System.out.println("Broadening beta ("+beta+")");
					}
					labelled.clear();
					labelled.addAll(poslbl);
					labelled.addAll(neglbl);
					alTime += System.currentTimeMillis() - al0;
					continue big;
				}
				n_added = 0;
				loop: while(neglbl.size() < 1) {
					f0r: for(Couple c : negInformative)
						if(!poslbl.contains(c) && !neglbl.contains(c)) {
							if(n_added == k)
								break f0r;
							if(askOracle(c) == false) {
								neglbl.add(c);
								continue loop;
							} else {
								n_added++;
								poslbl.add(c);
								if(n_added == 2*k)
									break f0r;
							}
						}
					System.out.println("Labeled pos: "+poslbl.size());
					System.out.println("Labeled neg: "+neglbl.size());
					System.out.print("Too few negatives found. ");
					if(model == null) {
						theta = theta - _beta;
						System.out.println("Shifting theta ("+theta+")");
					} else {
						beta = beta + _beta;
						System.out.println("Broadening beta ("+beta+")");
					}
					labelled.clear();
					labelled.addAll(poslbl);
					labelled.addAll(neglbl);
					alTime += System.currentTimeMillis() - al0;
					continue big;
				}
				
				labelled.clear();
				labelled.addAll(poslbl);
				labelled.addAll(neglbl);
				
				System.out.println("Pos Labeled:");
				for(Couple c : poslbl) {
					for(double d : c.getDistances())
						System.out.print(d+", ");
					System.out.println("\t"+c+"\t"+c.getGamma());
				}
				System.out.println("Neg Labeled:");
				for(Couple c : neglbl) {
					for(double d : c.getDistances())
						System.out.print(d+", ");
					System.out.println("\t"+c+"\t"+c.getGamma());
				}
		
				System.out.println("Labeled pos: "+poslbl.size());
				System.out.println("Labeled neg: "+neglbl.size());
		        System.out.println("Questions submitted: "+query_counter);
				
				long ml0 = System.currentTimeMillis();
				alTime += ml0 - al0;
		        
		        // perceptron learning phase
		        TreeSet<Couple> fpC = new TreeSet<Couple>();
		        TreeSet<Couple> fnC = new TreeSet<Couple>();
		        for(int i_perc=0; true; i_perc++) {
		        	System.out.println("\nPerceptron: iteration #"+i_perc);
		        	fnC.clear(); fpC.clear();
		        	tp0 = 0; fp0 = 0; tn0 = 0; fn0 = 0;
		        	
		        	boolean classSucceed = traceSvm(poslbl, neglbl);
		        	
					for(Couple c : poslbl)
						if(classify(c))
							tp0++;
						else {
							fn0++;
							fnC.add(c);
						}
					for(Couple c : neglbl)
						if(classify(c)) {
							fp0++;
							fpC.add(c);
						} else
							tn0++;
					
			        if(perceptronEndCondition(classSucceed, i_perc, getFScore(tp0, fp0, tn0, fn0)))
			        	break;
			        else {
			        	updateWeights(fpC, fnC);
			        	updateSimilarities(fpC, fnC);
			        }
			    }
		        
		        mlTime += System.currentTimeMillis() - ml0;
		        
		        break;
			}
		}
        
        for(Property p : props)
        	if(p.getDatatype() == Property.TYPE_STRING)
        		System.out.println("WEIGHTS ("+p.getName()+"): "+p.getFilter().getWeights());
        
		System.out.println("== EXECUTION TIME (seconds) ==");
		System.out.println("Preparation     \t" + prTime/1000.0);
		System.out.println("Active learning \t" + alTime/1000.0);
		System.out.println("SVM + Perceptron\t" + mlTime/1000.0);
		System.out.println("Total exec. time\t" + (prTime+alTime+mlTime)/1000.0);

		// testing...
		
		System.out.println("");

		if( !fastEvaluation(sources, targets) )
			subsetEvaluation(sources, targets);
		
//		evaluation(sources, targets);
		
		createOctaveScript(sources, targets, props);
		
		try {
//			Svm3D.draw(model, problem, theta_plot, sv_d, theta0);
		} catch (ArrayIndexOutOfBoundsException e) {
			System.out.println("3D: no plot (n < 3).");
		}
		
	}

	private static boolean fastEvaluation(TreeSet<Resource> sources, TreeSet<Resource> targets) {
		double tp = 0, tn = 0, fp = 0, fn = 0;
		
		ArrayList<String> mapping = Test.getOraclesAnswers();
		
		for(String map : mapping) {
			String[] ids = map.split("#");
			Resource src = null, tgt = null;
			for(Resource s : sources)
				if(s.getID().equals(ids[0])) {
					src = s;
					break;
				}
			for(Resource t : targets)
				if(t.getID().equals(ids[1])) {
					tgt = t;
					break;
				}
			Couple c = new Couple(src, tgt);
			for(int i=0; i<n; i++) {
				Property p = props.get(i);
				double d = p.getFilter().getDistance(src.getPropertyValue(p.getName()), tgt.getPropertyValue(p.getName()));
                if(p.getDatatype() == Property.TYPE_NUMERIC)
                	d = normalize(d, i);
				c.setDistance(d, p.getIndex());
			}
			if(classify(c))
				tp++;
			else
				fn++;
		}

		TreeSet<Couple> intersection = new TreeSet<Couple>();
		beta = 0;
		boolean allInfinite = true, performCartesianP = true;
		for(int i=0; i<n; i++) {
			Property p = props.get(i);
			double theta_i = computeMonteCarlo(i); // computeThreshold(i);
			if(!Double.isInfinite(theta_i))
				allInfinite = false;
			System.out.println("Property: "+p.getName()+"\ttheta_"+i+" = "+theta_i);
			if(!p.isNoisy()) {
				if(performCartesianP) { // first property works on the entire Cartesian product.
					if(p.getDatatype() == Property.TYPE_STRING) {
						NewNgFilter ngf = new NewNgFilter(p);
						ngf.setWeights(p.getFilter().getWeights());
						intersection = ngf.filter(sources, targets, p.getName(), theta_i);
					} else {
						intersection = p.getFilter().filter(sources, targets, p.getName(), theta_i);
					}
					performCartesianP = false;
				} else {
					if(p.getDatatype() == Property.TYPE_STRING) {
						NewNgFilter ngf = new NewNgFilter(p);
						ngf.setWeights(p.getFilter().getWeights());
						merge(intersection, ngf.filter(intersection, p.getName(), theta_i), i);
					} else {
						merge(intersection, p.getFilter().filter(intersection, p.getName(), theta_i), i);
					}
				}
			}
			System.out.println("intersection size: "+intersection.size());
		}
		if(allInfinite) {
			System.out.println("Cannot evaluate precision, no thresholds available.");
			getFScore(tp, fp, tn, fn);
			return false;
		}

		for(int i=0; i<n; i++) {
			Property p = props.get(i);
			if(p.isNoisy()) {
				for(Couple c : intersection) {
					Resource s = c.getSource();
					Resource t = c.getTarget();
					double d = props.get(i).getFilter().getDistance(s.getPropertyValue(p.getName()), t.getPropertyValue(p.getName()));
					c.setDistance(d, p.getIndex());
				}
			}
		}

		normalizeNumValues(intersection);
		
		double negIn = 0;
		for(Couple c : intersection)
			if(!askOracle(c.toString())) {
				if(!classify(c))
					tn++;
				else
					fp++;
				negIn++;
			}
		
		double negOut = sources.size() * targets.size() - mapping.size() - negIn;
		
		tn = tn + negOut;
		
		System.out.println();
		getFScore(tp, fp, tn, fn);
		
		return true;
	}

	private static void updateSimilarities(TreeSet<Couple> fpC,
			TreeSet<Couple> fnC) {
		for(Property p : props) {
			if(p.getDatatype() == Property.TYPE_STRING) {
				StandardFilter filter = p.getFilter();
				String pname = p.getName();
				int index = p.getIndex();
				for(Couple c : fpC)
					c.setDistance(filter.getDistance(c.getSource().getPropertyValue(pname),
							c.getTarget().getPropertyValue(pname)), index);
				for(Couple c : fnC)
					c.setDistance(filter.getDistance(c.getSource().getPropertyValue(pname),
							c.getTarget().getPropertyValue(pname)), index);
			}
		}
	}

	private static void updateWeights(TreeSet<Couple> fpC, TreeSet<Couple> fnC) {
		for(Property p : props) {
			if(p.getDatatype() == Property.TYPE_STRING) {
				// TODO find alternative to casting: method .prepare() in StandardFilter
				WeightedNgramFilter rf = (WeightedNgramFilter)(p.getFilter());
				String pname = p.getName();
				for(Couple c : fpC)
					rf.prepareNgCache(c.getSource().getPropertyValue(pname), 
							c.getTarget().getPropertyValue(pname), false, 3);
				for(Couple c : fnC)
					rf.prepareNgCache(c.getSource().getPropertyValue(pname), 
							c.getTarget().getPropertyValue(pname), true, 3);
				rf.updateWeights();
			}
		}
	}

	private static boolean perceptronEndCondition(boolean cl, int i_perc, double f1) {
		return !cl || i_perc >= max_iter_perceptron || f1 == 1.0;
	}

	private static void normalizeNumValues(TreeSet<Couple> intersection) {
		// normalize numeric values... [0,1]
		for(int i=0; i<props.size(); i++)
			if(props.get(i).getDatatype() == Property.TYPE_NUMERIC)
				for(Couple c : intersection)
					c.setDistance( normalize(c.getDistanceAt(i), i), i );
	}

    private static double computeThreshold(int j) {
        // Default linear classifier...
        if(model == null) {
			double sum = 0;
			for(int i=0; i<n; i++)
				if(i != j)
					sum += w[i];
			double d = (theta - sum) / w[j] - beta;
			if(d <= 0)
				props.get(j).setNoisy(true);
			else
				props.get(j).setNoisy(false);
			return d;
        }
        // Polynomial homogeneous II-degree classifiers...
        // if weight is not positive, property is noisy (no lower bound)
        if(w_linear[j] <= 0) {
                props.get(j).setNoisy(true);
                return 0.0;
        }
        ArrayList<String> suffix = buildPhiSuffix();
        double r2 = Math.sqrt(2);
        // optimal values for other coordinates
        double[] x = new double[n];
        for(int i=0; i<n; i++)
//              if(w_linear[i] > 0)
                        x[i] = 1;
//              else
//                      x[i] = 0;
        // calculate b, c
        double b = 0.0, c = 0.0;
        for(int i=0; i<suffix.size(); i++) {
                String[] s = suffix.get(i).split(",");
                if(s[0].equals(""+j))
                        b += w_linear[n + i] * x[Integer.parseInt(s[1])];
                else if(s[1].equals(""+j))
                        b += w_linear[n + i] * x[Integer.parseInt(s[0])];
                else
                        c += w_linear[n + i] * x[Integer.parseInt(s[0])] * x[Integer.parseInt(s[1])];
        }
        b = b * r2 / w_linear[j];
        c = c * r2;
        // quadratic terms
        for(int i=0; i<n; i++)
                if(i != j)
                        c += w_linear[i]; // * Math.pow(x[i], 2);
        // is "theta-beta" right for all theta?
        if(theta > 0)
                c = (c - (theta)) / w_linear[j];
        else
                c = (c - (theta)) / w_linear[j];
        // II-degree equation formula (a=1)
        double x1 = (- b + Math.sqrt(Math.pow(b, 2) - 4 * c)) / 2;
        double x2 = (- b - Math.sqrt(Math.pow(b, 2) - 4 * c)) / 2;
        if(x2 < 0 && 0 < x1 && x1 <= 1) {
                props.get(j).setNoisy(false);
                return x1 - beta;
        }
        props.get(j).setNoisy(true);
        return Double.NEGATIVE_INFINITY;
    }

	private static double computeMonteCarlo(int j) {
		if(model == null)
			return computeThreshold(j);
		double min = 1;
		for(int a=0; a<10000; a++) {
			double[] x = new double[n];
			for(int i=0; i<n; i++)
				x[i] = Math.random();
			if(classify(x)) {
				if(x[j] < min)
					min = x[j];
			}
		}
		min = (int)((min-beta)*100) / 100.0;
		if(min < 0.1) {
			min = Double.NEGATIVE_INFINITY;
			props.get(j).setNoisy(true);
		} else {
			props.get(j).setNoisy(false);
		}
		System.out.println("MC method for "+j+" = "+min);
		return min;
	}


	@SuppressWarnings("unused")
	private static void evaluation(TreeSet<Resource> sources, TreeSet<Resource> targets) {
		long cnt = 0;
		double tp = 0, tn = 0, fp = 0, fn = 0;
		double[] val = new double[n];
		
		for(Resource s : sources) {
			for(Resource t : targets) {
				for(int j=0; j<props.size(); j++) {
					Property prop = props.get(j);
					String p = prop.getName();
					val[j] = prop.getFilter().getDistance(s.getPropertyValue(p), t.getPropertyValue(p));
	                if(prop.getDatatype() == Property.TYPE_NUMERIC)
	                	val[j] = normalize(val[j], j);
				}
				if(askOracle(s.getID()+"#"+t.getID())) {
					if(classify(val))
						tp++;
					else
						fn++;
				} else {
					if(classify(val))
						fp++;
					else
						tn++;
				}
				cnt++;
				if(cnt % 100000 == 0)
					System.out.print(".");
			}
		}
		System.out.println();
        
		getFScore(tp, fp, tn, fn);
	}
	
	private static double getFScore(double tp, double fp, double tn, double fn) {
        double pre = tp+fp != 0 ? tp / (tp + fp) : 0;
        double rec = tp+fn != 0 ? tp / (tp + fn) : 0;
        double f1 = pre+rec != 0 ? 2 * pre * rec / (pre + rec) : 0;
        System.out.println("pre = "+pre+", rec = "+rec);
        System.out.println("f1 = "+f1+" (tp="+tp+", fp="+fp+", tn="+tn+", fn="+fn+")");
        return f1;
	}


	private static void subsetEvaluation(TreeSet<Resource> sources, TreeSet<Resource> targets) {
//		double tp = tp0, tn = tn0, fp = fp0, fn = fn0;
		double tp = 0, tn = 0, fp = 0, fn = 0;
		
		System.out.print("\nEvaluation esteem");
		
		final int BREAK_AT = 100000;
        svm_node[][] x2 = new svm_node[problem.l+BREAK_AT][n];
        for(int i=0; i<problem.x.length; i++)
        	for(int j=0; j<problem.x[i].length; j++)
        		x2[i][j] = problem.x[i][j];
        double[] y2 = new double[problem.l+BREAK_AT];
        for(int i=0; i<problem.y.length; i++)
    		y2[i] = problem.y[i];
        
        ArrayList<Resource> src = new ArrayList<Resource>(sources);
        ArrayList<Resource> tgt = new ArrayList<Resource>(targets);
        ArrayList<String> ids = new ArrayList<String>();
        
        for(int i=0; i<BREAK_AT; i++) {
        	Resource s = src.get( (int)(src.size()*Math.random()) );
        	Resource t = tgt.get( (int)(tgt.size()*Math.random()) );
        	if(ids.contains(s.getID()+"#"+t.getID())) {
        		i--;
        	} else {
				double val;
				for(int j=0; j<props.size(); j++) {
					Property prop = props.get(j);
					String p = prop.getName();
					val = prop.getFilter().getDistance(s.getPropertyValue(p), t.getPropertyValue(p));
	                x2[problem.l+i][j] = new svm_node();
	                x2[problem.l+i][j].index = j;
	                if(prop.getDatatype() == Property.TYPE_NUMERIC)
	                	x2[problem.l+i][j].value = normalize(val, j);
	                else
	                	x2[problem.l+i][j].value = val;
				}
				ids.add(s.getID()+"#"+t.getID());
				if(i % 1000 == 0)
					System.out.print(".");
        	}
		}
		System.out.println("");
        
		// recover old values
		for(int j=0; j<props.size(); j++)
			if(props.get(j).getDatatype() == Property.TYPE_NUMERIC)
		        for(int i=0; i<orig_x.length; i++)
		    		x2[i][j].value = orig_x[i][j].value;
		
		// predicts the test set
		for(int i=0; i<ids.size(); i++) {
			if(askOracle(ids.get(i))) {
				if(classify(x2[problem.l+i])) tp++;
				else { fn++; System.out.println("FN: "+ids.get(i)); }
				y2[problem.l+i] = 1;
			} else {
				if(classify(x2[problem.l+i])) { fp++; System.out.println("FP: "+ids.get(i)); }
				else tn++;
				y2[problem.l+i] = -1;
			}
		}
        
		problem.x = x2;
		problem.y = y2;
		problem.l += ids.size();
		
		getFScore(tp, fp, tn, fn);
	}

	private static double normalize(double value, int j) {
		// incomplete information means similarity = 0 
		if(Double.isNaN(value))
			return 0.0;
		ArrayList<Double> ext = extrema.get(j);
		double maxS = ext.get(0), minS = ext.get(1), maxT = ext.get(2), minT = ext.get(3);
		double denom = Math.max(maxT - minS, maxS - minT);
		if(denom == 0.0)
			return 1.0;
		else
			return 1.0 - value / denom;
	}

	private static void computeExtrema(int index, TreeSet<Resource> sources, TreeSet<Resource> targets) {
		ArrayList<Double> ext = new ArrayList<Double>();
		String pname = props.get(index).getName();
		double maxS = Double.NEGATIVE_INFINITY, minS = Double.POSITIVE_INFINITY;
		for(Resource s : sources) {
			double d = ValueParser.parse( s.getPropertyValue(pname) );
			if(d > maxS) maxS = d;
			if(d < minS) minS = d;
		}
		ext.add(maxS);
		ext.add(minS);
		double maxT = Double.NEGATIVE_INFINITY, minT = Double.POSITIVE_INFINITY;
		for(Resource t : targets) {
			double d = ValueParser.parse( t.getPropertyValue(pname) );
			if(d > maxT) maxT = d;
			if(d < minT) minT = d;
		}
		ext.add(maxT);
		ext.add(minT);
		extrema.put(index, ext);
		((MahalaFilter) props.get(index).getFilter()).setExtrema(ext);
		System.out.println(ext.toString());
	}

	private static boolean blockingEndCondition(int intersSize, int cartesianP) {
		return intersSize >= 2*k+query_counter || cartesianP < max_queries+query_counter;
	}

	private static void merge(TreeSet<Couple> intersection, TreeSet<Couple> join, int index) {
	    Iterator<Couple> e = intersection.iterator();
	    while (e.hasNext()) {
	    	Couple c = e.next();
	        if (!join.contains(c))
		        e.remove();
	        else {
	        	for(Couple cj : join)
	        		if(cj.equals(c)) {
	        			c.setDistance(cj.getFirstDistance(), index);
	        			break;
	        		}
	        }	
	    }
	}

	private static boolean classify(Couple c) {
		if(model == null) {
			// Default classifier is always set to linear.
			double sum = 0.0;
			ArrayList<Double> dist = c.getDistances();
			for(int i=0; i<dist.size(); i++)
				sum += dist.get(i) * w[i];
			return sum >= theta; 
		}
        svm_node[] node = new svm_node[n];
        for(int i=0; i<n; i++) {
        	node[i] = new svm_node();
        	node[i].index = i;
        	node[i].value = c.getDistanceAt(i);
        }
        return classify(node);
	}

	private static boolean classify(double[] val) {
        svm_node[] node = new svm_node[n];
        for(int i=0; i<n; i++) {
        	node[i] = new svm_node();
        	node[i].index = i;
        	node[i].value = val[i];
        }
        return classify(node);
	}
	
	private static boolean classify(svm_node[] node) {
        if(svm.svm_predict(model, node) == 1.0)
        	return true;
        else return false;
	}
	

	// gamma = distance from classifier
    private static double computeGamma(ArrayList<Double> dist) {
		if(model == null) {
			// Default classifier...
	        double numer = 0.0, denom = 0.0;
	        for(int i=0; i<dist.size(); i++) {
	            numer += dist.get(i) * w[i];
	            denom += Math.pow(w[i], 2);
	        }
	        numer -= theta;
	        return Math.abs(numer/Math.sqrt(denom));
		}
		// All classifiers...
		double[] x = new double[dist.size()];
		for(int i=0; i<dist.size(); i++)
			x[i] = dist.get(i);
		double[] phi = phi(x);
		int m = phi.length;
		// calculate Q
		double[] q = new double[m];
		for(int i=0; i<m; i++)
			if(w_linear[i] != 0) {
				q[i] = theta / w_linear[i];
				break;
			}
		// calculate unit vector wu
		double norm = 0;
		for(int i=0; i<m; i++)
			norm += Math.pow(w_linear[i], 2);
		norm = Math.sqrt(norm);
		double[] wu = new double[n];
		for(int i=0; i<n; i++)
			wu[i] = w_linear[i] / norm;
		// calculate t = phi(X - Q) . wu
		double[] phixq = new double[m];
		for(int i=0; i<m; i++)
			phixq[i] = phi[i] - q[i];
		double t = 0;
		for(int i=0; i<n; i++)
			t += phixq[i] * wu[i];
		// calculate P'
		double[] p1 = new double[n];
		for(int i=0; i<n; i++)
			p1[i] = phi[i] - t * wu[i];
		// calculate segment XP
		double sum = 0;
		for(int i=0; i<n; i++)
			if(p1[i] >= 0)
				sum += Math.pow(dist.get(i) - phiInverse(p1[i]), 2);
			else // anti-transformation is out of the similarity space
				sum += Math.pow(dist.get(i), 2);
		sum = Math.sqrt(sum);
		if(Double.isNaN(sum))
			System.out.println();
		return sum;
    }
    
    private static ArrayList<String> buildPhiSuffix() {
    	ArrayList<String> suffix = new ArrayList<String>();
    	for(int i=0; i<n-1; i++)
    		for(int j=i+1; j<n; j++)
    			if(i != j)
    				suffix.add(i+","+j);
    	return suffix;
    }
    
    private static double[] phi(double[] x) {
    	// assuming DEGREE = 2 and COEF0 = 0
    	int n1 = (int) (Math.pow(n, 2) + n) / 2;
    	double[] phi = new double[n1];
    	for(int i=0; i<n; i++)
    		phi[i] = Math.pow(x[i], 2);
    	double r2 = Math.sqrt(2);
    	int p = n;
    	for(int i=0; i<n-1; i++)
    		for(int j=i+1; j<n; j++) {
        		phi[p] = r2 * x[i] * x[j];
        		p++;
    		}
    	return phi;
    }

    private static double phiInverse(double x) {
		return Math.sqrt(x);
	}

	private static boolean askOracle(Couple c) {
    	query_counter++;
    	String ids = c.getSource().getID()+"#"+c.getTarget().getID();
    	boolean b = askOracle(ids);
    	if(b)
    		c.setPositive(true);
    	else
    		c.setPositive(false);
		return b;
	}

    private static boolean askOracle(String ids) {
		return Test.askOracle(ids); // TODO remove me & add interaction
	}

	private static void createOctaveScript(TreeSet<Resource> sources, TreeSet<Resource> targets, ArrayList<Property> props) {
		String[] xp = new String[n];
		String[] xn = new String[n];
		for(int i=0; i<n; i++) {
			xp[i] = "x"+i+"p = [";
			xn[i] = "x"+i+"n = [";
		}
		int NEGATIVES = 5000;
		ArrayList<String> mapping = Test.getOraclesAnswers();
		
		for(String map : mapping) {
			String[] ids = map.split("#");
			Resource src = null, tgt = null;
			for(Resource s : sources)
				if(s.getID().equals(ids[0])) {
					src = s;
					break;
				}
			for(Resource t : targets)
				if(t.getID().equals(ids[1])) {
					tgt = t;
					break;
				}
			for(int i=0; i<n; i++) {
				Property p = props.get(i);
				double d = p.getFilter().getDistance(src.getPropertyValue(p.getName()), tgt.getPropertyValue(p.getName()));
                if(p.getDatatype() == Property.TYPE_NUMERIC)
                	d = normalize(d, i);
                xp[i] += d + " ";
			}
		}

		String[] names = new String[n];
		StandardFilter[] filters = new StandardFilter[n];
		for(int i=0; i<n; i++) {
			filters[i] = props.get(i).getFilter();
			names[i] = props.get(i).getName();
		}
		ArrayList<Resource> src = new ArrayList<Resource>(sources);
		ArrayList<Resource> tgt = new ArrayList<Resource>(targets);
		
		for(int c=0; c<NEGATIVES; c++) {
			Resource s = src.get((int) (src.size()*Math.random()));
			Resource t = tgt.get((int) (tgt.size()*Math.random()));
			double[] d = new double[n];
			for(int i=0; i<n; i++) {
				double sim = filters[i].getDistance(s.getPropertyValue(names[i]), t.getPropertyValue(names[i]));
                if(props.get(i).getDatatype() == Property.TYPE_NUMERIC)
                	sim = normalize(sim, i);
				d[i] = Double.isNaN(sim) ? 0 : sim;
			}
			if(!askOracle(s.getID()+"#"+t.getID())) {
				for(int i=0; i<n; i++)
					xn[i] += d[i] + " ";
			} else c--;
		}
		System.out.println();
		
		String points = "";
		for(int i=0; i<n; i++) {
			xp[i] += "];\n";
			xn[i] += "];\n";
			points += xp[i] + xn[i];
		}

		try{
			// Create file 
			FileWriter fstream = new FileWriter("octave/points"+System.currentTimeMillis()+".m");
			BufferedWriter out = new BufferedWriter(fstream);
			out.write(points);
			//Close the output stream
			out.close();
		} catch (Exception e){//Catch exception if any
			System.err.println("Error: " + e.getMessage());
		}
	}

    private static boolean traceSvm(ArrayList<Couple> poslbl, ArrayList<Couple> neglbl) {
    	
        int size = poslbl.size() + neglbl.size();

        // build x,y vectors
        svm_node[][] x = new svm_node[size][n];
        double[] y = new double[size];
        for(int i=0; i<poslbl.size(); i++) {
            ArrayList<Double> arr = poslbl.get(i).getDistances();
            for(int j=0; j<arr.size(); j++) {
                x[i][j] = new svm_node();
                x[i][j].index = j;
                x[i][j].value = arr.get(j);
            }
            y[i] = 1;
        }
        for(int i=poslbl.size(); i<size; i++) {
            ArrayList<Double> arr = neglbl.get(i-poslbl.size()).getDistances();
            for(int j=0; j<arr.size(); j++) {
                x[i][j] = new svm_node();
                x[i][j].index = j;
                x[i][j].value = arr.get(j);
            }
            y[i] = -1;
        }
        
        // configure model
        // POLY: (gamma*u'*v + coef0)^degree
        problem = new svm_problem();
        problem.l = size;
        problem.x = x;
        problem.y = y;
        orig_x = x.clone();
        svm_parameter parameter = new svm_parameter();
        parameter.C = 1E+4;
        parameter.svm_type = svm_parameter.C_SVC;
        parameter.kernel_type = KERNEL;
        if(KERNEL == svm_parameter.POLY) {
			parameter.degree = DEGREE; // default: 3
			parameter.coef0  = COEF0; // default: 0
			parameter.gamma  = GAMMA; // default: 1/n
        } 
        parameter.eps = 1E-4;
        model = svm.svm_train(problem, parameter);
        // sv = ( nSV ; n )
        svm_node[][] sv = model.SV;
        // no support vectors
        if(sv.length == 0)
        	return false;
        // sv_coef = ( 1 ; nSV )
        double[][] sv_coef = model.sv_coef;
        
        sv_d = new double[sv.length][n];
        for(int j=0; j<sv.length; j++)
            for(int i=0; i<sv[j].length; i++)
            	sv_d[j][i] = sv[j][i].value;
        
        // w = sv' * sv_coef' = (sv_coef * sv)' = ( n ; 1 )
        double[][] phis = new double[sv.length][];
        for(int i=0; i<phis.length; i++)
        	phis[i] = phi(sv_d[i]);
        
        w_linear = new double[phis[0].length];
        
        for(int i=0; i<phis.length; i++)
        	for(int j=0; j<phis[i].length; j++)
        		w_linear[j] += sv_coef[0][i] * phis[i][j];
        
        int signum = (model.label[0] == -1.0) ? -1 : 1;
        theta = signum * model.rho[0];
        
        // theta is normally at the first member in the classification inequality
        theta_plot = -theta;

    	for(int i=0; i<w_linear.length; i++) {
    		w_linear[i] = signum * w_linear[i] / Math.abs(theta);
    		System.out.println("w_linear["+i+"] = "+w_linear[i]);
    	}
		theta = theta / Math.abs(theta);

        System.out.println("theta = "+theta);
        
        return true;
    }

}
